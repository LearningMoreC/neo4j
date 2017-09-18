/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.recovery;

import java.io.IOException;

import org.neo4j.helpers.Exceptions;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.impl.store.UnderlyingStorageException;
import org.neo4j.kernel.impl.transaction.log.LogEntryCursor;
import org.neo4j.kernel.impl.transaction.log.LogPosition;
import org.neo4j.kernel.impl.transaction.log.LogVersionedStoreChannel;
import org.neo4j.kernel.impl.transaction.log.PhysicalLogFile;
import org.neo4j.kernel.impl.transaction.log.PhysicalLogFiles;
import org.neo4j.kernel.impl.transaction.log.ReadAheadLogChannel;
import org.neo4j.kernel.impl.transaction.log.ReadableClosablePositionAwareChannel;
import org.neo4j.kernel.impl.transaction.log.ReadableLogChannel;
import org.neo4j.kernel.impl.transaction.log.entry.CheckPoint;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntry;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryCommit;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryReader;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryStart;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryVersion;
import org.neo4j.kernel.impl.transaction.log.entry.UnsupportedLogVersionException;
import org.neo4j.kernel.monitoring.Monitors;
import org.neo4j.unsafe.impl.internal.dragons.FeatureToggles;

import static java.lang.String.format;
import static org.neo4j.kernel.impl.transaction.log.LogVersionRepository.INITIAL_LOG_VERSION;

/**
 * This class collects information about the latest entries in the transaction log. Since the only way we have to collect
 * said information is to scan the transaction log from beginning to end, which is costly, we do this once and save the
 * result for others to consume.
 * <p>
 * Due to the nature of transaction logs and log rotation, a single transaction log file has to be scanned forward, and
 * if the required data is not found we search backwards through log file versions.
 */
public class LogTailScanner
{
    static long NO_TRANSACTION_ID = -1;
    private final PhysicalLogFiles logFiles;
    private final FileSystemAbstraction fileSystem;
    private final LogEntryReader<ReadableClosablePositionAwareChannel> logEntryReader;
    private LogTailInformation logTailInformation;
    private final LogTailScannerMonitor monitor;

    public LogTailScanner( PhysicalLogFiles logFiles, FileSystemAbstraction fileSystem,
            LogEntryReader<ReadableClosablePositionAwareChannel> logEntryReader, Monitors monitors )
    {
        this.logFiles = logFiles;
        this.fileSystem = fileSystem;
        this.logEntryReader = logEntryReader;
        this.monitor = monitors.newMonitor( LogTailScannerMonitor.class );
    }

    private LogTailInformation findLogTail() throws IOException
    {
        final long highestLogVersion = logFiles.getHighestLogVersion();
        long version = highestLogVersion;
        long versionToSearchForCommits = highestLogVersion;
        LogEntryStart latestStartEntry = null;
        long oldestStartEntryTransaction = -1;
        long oldestVersionFound = -1;
        LogEntryVersion latestLogEntryVersion = null;
        boolean startRecordAfterCheckpoint = false;
        boolean corruptedTransactionLogs = false;

        while ( version >= INITIAL_LOG_VERSION )
        {
            LogVersionedStoreChannel channel =
                    PhysicalLogFile.tryOpenForVersion( logFiles, fileSystem, version, false );
            if ( channel == null )
            {
                break;
            }

            oldestVersionFound = version;
            CheckPoint latestCheckPoint = null;
            ReadableLogChannel recoveredDataChannel = new ReadAheadLogChannel( channel );
            try ( LogEntryCursor cursor = new LogEntryCursor( logEntryReader, recoveredDataChannel ) )
            {
                LogEntry entry;
                while ( cursor.next() )
                {
                    entry = cursor.get();

                    // Collect data about latest checkpoint
                    if ( entry instanceof CheckPoint )
                    {
                        latestCheckPoint = entry.as();
                    }
                    else if ( entry instanceof LogEntryCommit )
                    {
                        if ( oldestStartEntryTransaction == NO_TRANSACTION_ID )
                        {
                            oldestStartEntryTransaction = ((LogEntryCommit) entry).getTxId();
                        }
                    }
                    else if ( entry instanceof LogEntryStart )
                    {
                        LogEntryStart startEntry = entry.as();
                        if ( version == versionToSearchForCommits )
                        {
                            latestStartEntry = startEntry;
                        }
                        startRecordAfterCheckpoint = true;
                    }

                    // Collect data about latest entry version, only in first log file
                    if ( version == versionToSearchForCommits || latestLogEntryVersion == null )
                    {
                        latestLogEntryVersion = entry.getVersion();
                    }
                }
            }
            catch ( Throwable t )
            {
                if ( Exceptions.contains( t, UnsupportedLogVersionException.class ) )
                {
                    if ( FeatureToggles.flag( LogTailScanner.class, "force", false ) )
                    {
                        monitor.forced( t );
                    }
                    else
                    {
                        throw new RuntimeException( format( "Unsupported transaction log version found. " +
                                "To force transactional processing anyway and trip non recognised transactions please " +
                                "use %s. By using this flag you can lose part of your transactions log. This operation is irretrievable." +
                                " ", FeatureToggles.toggle( LogTailScanner.class, "force", true ) ), t );
                    }
                }
                corruptedTransactionLogs = true;
                monitor.corruptedLogFile( version, t );
            }

            if ( latestCheckPoint != null )
            {
                return checkpointTailInformation( highestLogVersion, latestStartEntry, oldestVersionFound,
                        latestLogEntryVersion, latestCheckPoint, corruptedTransactionLogs );
            }

            version--;

            // if we have found no commits in the latest log, keep searching in the next one
            if ( latestStartEntry == null )
            {
                versionToSearchForCommits--;
            }
        }

        return new LogTailInformation( corruptedTransactionLogs || startRecordAfterCheckpoint,
                oldestStartEntryTransaction, oldestVersionFound, highestLogVersion, latestLogEntryVersion );
    }

    protected LogTailInformation checkpointTailInformation( long highestLogVersion, LogEntryStart latestStartEntry,
            long oldestVersionFound, LogEntryVersion latestLogEntryVersion, CheckPoint latestCheckPoint,
            boolean corruptedTransactionLogs ) throws IOException
    {
        LogPosition checkPointLogPosition = latestCheckPoint.getLogPosition();
        ExtractedTransactionRecord transactionRecord = extractFirstTxIdAfterPosition( checkPointLogPosition, highestLogVersion );
        long firstTxIdAfterPosition = transactionRecord.getId();
        boolean startRecordAfterCheckpoint = (firstTxIdAfterPosition != NO_TRANSACTION_ID) ||
                ((latestStartEntry != null) &&
                        (latestStartEntry.getStartPosition().compareTo( latestCheckPoint.getLogPosition() ) >= 0));
        boolean corruptedLogs = transactionRecord.isFailure() || corruptedTransactionLogs;
        return new LogTailInformation( latestCheckPoint, corruptedLogs || startRecordAfterCheckpoint,
                firstTxIdAfterPosition, oldestVersionFound, highestLogVersion, latestLogEntryVersion );
    }

    /**
     * Extracts txId from first commit entry, when starting reading at the given {@code position}.
     * If no commit entry found in the version, the reader will continue into next version(s) up till
     * {@code maxLogVersion} until finding one.
     *
     * @param initialPosition {@link LogPosition} to start scan from.
     * @param maxLogVersion max log version to scan.
     * @return value object that contains first transaction id of closes commit entry to {@code initialPosition},
     * or {@link LogTailInformation#NO_TRANSACTION_ID} if not found. And failure flag that will be set to true if
     * there was some exception during transaction log processing.
     * @throws IOException on channel close I/O error.
     */
    protected ExtractedTransactionRecord extractFirstTxIdAfterPosition( LogPosition initialPosition, long maxLogVersion ) throws IOException
    {
        LogPosition currentPosition = initialPosition;
        ExtractedTransactionRecord transactionRecord = new ExtractedTransactionRecord();
        while ( currentPosition.getLogVersion() <= maxLogVersion )
        {
            LogVersionedStoreChannel storeChannel = PhysicalLogFile.tryOpenForVersion( logFiles, fileSystem,
                    currentPosition.getLogVersion(), false );
            if ( storeChannel != null )
            {
                try
                {
                    storeChannel.position( currentPosition.getByteOffset() );
                    try ( ReadAheadLogChannel logChannel = new ReadAheadLogChannel( storeChannel );
                            LogEntryCursor cursor = new LogEntryCursor( logEntryReader, logChannel ) )
                    {
                        while ( cursor.next() )
                        {
                            LogEntry entry = cursor.get();
                            if ( entry instanceof LogEntryCommit )
                            {
                                transactionRecord.setId( ((LogEntryCommit) entry).getTxId() );
                                return transactionRecord;
                            }
                        }
                    }
                }
                catch ( Throwable t )
                {
                    monitor.corruptedLogFile( currentPosition.getLogVersion(), t );
                    transactionRecord.setFailure( true );
                    return transactionRecord;
                }
                finally
                {
                    storeChannel.close();
                }
            }

            currentPosition = LogPosition.start( currentPosition.getLogVersion() + 1 );
        }
        return transactionRecord;
    }

    /**
     * Collects information about the tail of the transaction log, i.e. last checkpoint, last entry etc.
     * Since this is an expensive task we do it once and reuse the result. This method is thus lazy and the first one
     * calling it will take the hit.
     * <p>
     * This is only intended to be used during startup. If you need to track the state of the tail, that can be done more
     * efficiently at runtime, and this method should then only be used to restore said state.
     *
     * @return snapshot of the state of the transaction logs tail at startup.
     * @throws UnderlyingStorageException if any errors occurs while parsing the transaction logs
     */
    public LogTailInformation getTailInformation() throws UnderlyingStorageException
    {
        if ( logTailInformation == null )
        {
            try
            {
                logTailInformation = findLogTail();
            }
            catch ( IOException e )
            {
                throw new UnderlyingStorageException( "Error encountered while parsing transaction logs", e );
            }
        }

        return logTailInformation;
    }

    static class ExtractedTransactionRecord
    {
        private long id;
        private boolean failure;

        ExtractedTransactionRecord()
        {
            id = NO_TRANSACTION_ID;
            failure = false;
        }

        public long getId()
        {
            return id;
        }

        public void setId( long id )
        {
            this.id = id;
        }

        public boolean isFailure()
        {
            return failure;
        }

        public void setFailure( boolean failure )
        {
            this.failure = failure;
        }
    }

    public static class LogTailInformation
    {

        public final CheckPoint lastCheckPoint;
        public final long firstTxIdAfterLastCheckPoint;
        public final long oldestLogVersionFound;
        public final long currentLogVersion;
        public final LogEntryVersion latestLogEntryVersion;
        private final boolean recordAfterCheckpoint;

        public LogTailInformation( boolean recordAfterCheckpoint, long firstTxIdAfterLastCheckPoint,
                long oldestLogVersionFound, long currentLogVersion,
                LogEntryVersion latestLogEntryVersion )
        {
            this( null, recordAfterCheckpoint, firstTxIdAfterLastCheckPoint, oldestLogVersionFound, currentLogVersion,
                    latestLogEntryVersion );
        }

        LogTailInformation( CheckPoint lastCheckPoint, boolean recordAfterCheckpoint, long firstTxIdAfterLastCheckPoint,
                long oldestLogVersionFound, long currentLogVersion, LogEntryVersion latestLogEntryVersion )
        {
            this.lastCheckPoint = lastCheckPoint;
            this.firstTxIdAfterLastCheckPoint = firstTxIdAfterLastCheckPoint;
            this.oldestLogVersionFound = oldestLogVersionFound;
            this.currentLogVersion = currentLogVersion;
            this.latestLogEntryVersion = latestLogEntryVersion;
            this.recordAfterCheckpoint = recordAfterCheckpoint;
        }

        public boolean commitsAfterLastCheckpoint()
        {
            return recordAfterCheckpoint;
        }
    }

}
