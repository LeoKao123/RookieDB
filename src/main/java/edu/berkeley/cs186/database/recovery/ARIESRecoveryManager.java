package edu.berkeley.cs186.database.recovery;

import edu.berkeley.cs186.database.Transaction;
import edu.berkeley.cs186.database.common.Pair;
import edu.berkeley.cs186.database.concurrency.DummyLockContext;
import edu.berkeley.cs186.database.io.DiskSpaceManager;
import edu.berkeley.cs186.database.memory.BufferManager;
import edu.berkeley.cs186.database.memory.Page;
import edu.berkeley.cs186.database.recovery.records.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Implementation of ARIES.
 */
public class ARIESRecoveryManager implements RecoveryManager {
    // Disk space manager.
    DiskSpaceManager diskSpaceManager;
    // Buffer manager.
    BufferManager bufferManager;

    // Function to create a new transaction for recovery with a given
    // transaction number.
    private Function<Long, Transaction> newTransaction;

    // Log manager
    LogManager logManager;
    // Dirty page table (page number -> recLSN).
    Map<Long, Long> dirtyPageTable = new ConcurrentHashMap<>();
    // Transaction table (transaction number -> entry).
    Map<Long, TransactionTableEntry> transactionTable = new ConcurrentHashMap<>();
    // true if redo phase of restart has terminated, false otherwise. Used
    // to prevent DPT entries from being flushed during restartRedo.
    boolean redoComplete;

    public ARIESRecoveryManager(Function<Long, Transaction> newTransaction) {
        this.newTransaction = newTransaction;
    }

    /**
     * Initializes the log; only called the first time the database is set up.
     * The master record should be added to the log, and a checkpoint should be
     * taken.
     */
    @Override
    public void initialize() {
        this.logManager.appendToLog(new MasterLogRecord(0));
        this.checkpoint();
    }

    /**
     * Sets the buffer/disk managers. This is not part of the constructor
     * because of the cyclic dependency between the buffer manager and recovery
     * manager (the buffer manager must interface with the recovery manager to
     * block page evictions until the log has been flushed, but the recovery
     * manager needs to interface with the buffer manager to write the log and
     * redo changes).
     * @param diskSpaceManager disk space manager
     * @param bufferManager buffer manager
     */
    @Override
    public void setManagers(DiskSpaceManager diskSpaceManager, BufferManager bufferManager) {
        this.diskSpaceManager = diskSpaceManager;
        this.bufferManager = bufferManager;
        this.logManager = new LogManager(bufferManager);
    }

    // Forward Processing //////////////////////////////////////////////////////

    /**
     * Called when a new transaction is started.
     *
     * The transaction should be added to the transaction table.
     *
     * @param transaction new transaction
     */
    @Override
    public synchronized void startTransaction(Transaction transaction) {
        this.transactionTable.put(transaction.getTransNum(), new TransactionTableEntry(transaction));
    }

    /**
     * Called when a transaction is about to start committing.
     *
     * A commit record should be appended, the log should be flushed,
     * and the transaction table and the transaction status should be updated.
     *
     * @param transNum transaction being committed
     * @return LSN of the commit record
     */
    @Override
    public long commit(long transNum) {
        // TODO(proj5): implement

        // fetch lastLSN of the transaction
        Long lastLSN = 0L;
        if (this.transactionTable.containsKey(transNum)) {
            lastLSN = this.transactionTable.get(transNum).lastLSN;
        }
        // write commit record to log
        Long commitLogLSN = this.logManager.appendToLog(new CommitTransactionLogRecord(transNum, lastLSN));

        // flush log up to commit record
        this.logManager.flushToLSN(commitLogLSN);

        // update transaction table status
        if (this.transactionTable.containsKey(transNum)) {
            TransactionTableEntry endTrans = this.transactionTable.get(transNum);
            endTrans.lastLSN = commitLogLSN;
            endTrans.transaction.setStatus(Transaction.Status.COMMITTING);
        } else {
            Transaction endTrans = this.newTransaction.apply(transNum);
            startTransaction(endTrans);
            this.transactionTable.get(transNum).lastLSN = commitLogLSN;
            this.transactionTable.get(transNum).transaction.setStatus(Transaction.Status.COMMITTING);
        }

        return commitLogLSN;
    }

    /**
     * Called when a transaction is set to be aborted.
     *
     * An abort record should be appended, and the transaction table and
     * transaction status should be updated. Calling this function should not
     * perform any rollbacks.
     *
     * @param transNum transaction being aborted
     * @return LSN of the abort record
     */
    @Override
    public long abort(long transNum) {
        // TODO(proj5): implement

        // fetch lastLSN of the transaction
        Long lastLSN = 0L;
        if (this.transactionTable.containsKey(transNum)) {
            lastLSN = this.transactionTable.get(transNum).lastLSN;
        }

        // create an abort log and append the log record to the log
        Long abortLogLSN = this.logManager.appendToLog(new AbortTransactionLogRecord(transNum, lastLSN));

        // update the transaction table
        if (this.transactionTable.containsKey(transNum)) {
            TransactionTableEntry abortTrans = this.transactionTable.get(transNum);
            abortTrans.lastLSN = abortLogLSN;
            abortTrans.transaction.setStatus(Transaction.Status.ABORTING);
        } else {
            Transaction abortTrans = newTransaction.apply(transNum);
            startTransaction(abortTrans);
            this.transactionTable.get(transNum).lastLSN = abortLogLSN;
            this.transactionTable.get(transNum).transaction.setStatus(Transaction.Status.ABORTING);
        }

        return abortLogLSN;
    }


    /**
     * Called when a transaction is cleaning up; this should roll back
     * changes if the transaction is aborting (see the rollbackToLSN helper
     * function below).
     *
     * Any changes that need to be undone should be undone, the transaction should
     * be removed from the transaction table, the end record should be appended,
     * and the transaction status should be updated.
     *
     * @param transNum transaction to end
     * @return LSN of the end record
     */
    @Override
    public long end(long transNum) {
        // TODO(proj5): implement

        TransactionTableEntry endingTrans;
        if (this.transactionTable.containsKey(transNum)) {
            endingTrans = this.transactionTable.get(transNum);
        } else {
            return -1;
        }

        // this should roll back changes if the transaction is aborting
        if (endingTrans.transaction.getStatus() == Transaction.Status.ABORTING) {
            rollbackToLSN(transNum, 0L);
        }
        // write end record
        Long endLogLSN = this.logManager.appendToLog(new EndTransactionLogRecord(transNum, endingTrans.lastLSN));
        endingTrans.lastLSN = endLogLSN;
        endingTrans.transaction.setStatus(Transaction.Status.COMPLETE);
        this.transactionTable.remove(transNum);
        return endLogLSN;
    }

    /**
     * Recommended helper function: performs a rollback of all of a
     * transaction's actions, up to (but not including) a certain LSN.
     * Starting with the LSN of the most recent record that hasn't been undone:
     * - while the current LSN is greater than the LSN we're rolling back to:
     *    - if the record at the current LSN is undoable:
     *       - Get a compensation log record (CLR) by calling undo on the record
     *       - Append the CLR
     *       - Call redo on the CLR to perform the undo
     *    - update the current LSN to that of the next record to undo
     *
     * Note above that calling .undo() on a record does not perform the undo, it
     * just creates the compensation log record.
     *
     * @param transNum transaction to perform a rollback for
     * @param LSN LSN to which we should rollback
     */
    private void rollbackToLSN(long transNum, long LSN) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        LogRecord lastRecord = logManager.fetchLogRecord(transactionEntry.lastLSN);
        long lastRecordLSN = lastRecord.getLSN();
        // Small optimization: if the last record is a CLR we can start rolling
        // back from the next record that hasn't yet been undone.
        long currentLSN = lastRecord.getUndoNextLSN().orElse(lastRecordLSN);

        // TODO(proj5) implement the rollback logic described above
        // undo all changes from lastLSN, by appending CLR log record
        TransactionTableEntry abortingTrans = this.transactionTable.get(transNum);
        Long traverseBackLSN = currentLSN;

        while (traverseBackLSN > LSN && traverseBackLSN != 0L) {
            LogRecord undoLog = this.logManager.fetchLogRecord(traverseBackLSN);
            if (undoLog.isUndoable()) {
                // create a clr log and append the log record to the log
                LogRecord clr = undoLog.undo(abortingTrans.lastLSN);
                Long clrLSN = this.logManager.appendToLog(clr);
                // update transaction table
                abortingTrans.lastLSN = clrLSN;
                clr.redo(this, this.diskSpaceManager, this.bufferManager);
            }
            traverseBackLSN = undoLog.getPrevLSN().get();
        }
    }

    /**
     * Called before a page is flushed from the buffer cache. This
     * method is never called on a log page.
     *
     * The log should be as far as necessary.
     *
     * @param pageLSN pageLSN of page about to be flushed
     */
    @Override
    public void pageFlushHook(long pageLSN) {
        logManager.flushToLSN(pageLSN);
    }

    /**
     * Called when a page has been updated on disk.
     *
     * As the page is no longer dirty, it should be removed from the
     * dirty page table.
     *
     * @param pageNum page number of page updated on disk
     */
    @Override
    public void diskIOHook(long pageNum) {
        if (redoComplete) dirtyPageTable.remove(pageNum);
    }

    /**
     * Called when a write to a page happens.
     *
     * This method is never called on a log page. Arguments to the before and after params
     * are guaranteed to be the same length.
     *
     * The appropriate log record should be appended, and the transaction table
     * and dirty page table should be updated accordingly.
     *
     * @param transNum transaction performing the write
     * @param pageNum page number of page being written
     * @param pageOffset offset into page where write begins
     * @param before bytes starting at pageOffset before the write
     * @param after bytes starting at pageOffset after the write
     * @return LSN of last record written to log
     */
    @Override
    public long logPageWrite(long transNum, long pageNum, short pageOffset, byte[] before,
                             byte[] after) {
        assert (before.length == after.length);
        assert (before.length <= BufferManager.EFFECTIVE_PAGE_SIZE / 2);
        // TODO(proj5): implement

        // fetch lastLSN of the transaction
        Long lastLSN = 0L;
        if (this.transactionTable.containsKey(transNum)) {
            lastLSN = this.transactionTable.get(transNum).lastLSN;
        }

        // create an update page log and append the log record to the log
        Long updateLogLSN = this.logManager.appendToLog(new UpdatePageLogRecord(transNum, pageNum, lastLSN, pageOffset, before, after));
        LogRecord updateLog = this.logManager.fetchLogRecord(updateLogLSN);

        // update the transaction table
        if (this.transactionTable.containsKey(transNum)) {
            TransactionTableEntry updateTrans = this.transactionTable.get(transNum);
            updateTrans.lastLSN = updateLogLSN;
            updateTrans.transaction.setStatus(Transaction.Status.RUNNING);
        } else {
            Transaction updateTrans = newTransaction.apply(transNum);
            startTransaction(updateTrans);
            this.transactionTable.get(transNum).lastLSN = updateLogLSN;
            this.transactionTable.get(transNum).transaction.setStatus(Transaction.Status.RUNNING);
        }

        // update the dirty page table
        if (!this.dirtyPageTable.containsKey(pageNum)) {
            this.dirtyPageTable.put(pageNum, updateLogLSN);
        }
        return updateLogLSN;
    }

    /**
     * Called when a new partition is allocated. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the partition is the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the allocation
     * @param partNum partition number of the new partition
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logAllocPart(long transNum, int partNum) {
        // Ignore if part of the log.
        if (partNum == 0) return -1L;
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new AllocPartLogRecord(transNum, partNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a partition is freed. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the partition is the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the partition be freed
     * @param partNum partition number of the partition being freed
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logFreePart(long transNum, int partNum) {
        // Ignore if part of the log.
        if (partNum == 0) return -1L;

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new FreePartLogRecord(transNum, partNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a new page is allocated. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the page is in the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the allocation
     * @param pageNum page number of the new page
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logAllocPage(long transNum, long pageNum) {
        // Ignore if part of the log.
        if (DiskSpaceManager.getPartNum(pageNum) == 0) return -1L;

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new AllocPageLogRecord(transNum, pageNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a page is freed. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the page is in the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the page be freed
     * @param pageNum page number of the page being freed
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logFreePage(long transNum, long pageNum) {
        // Ignore if part of the log.
        if (DiskSpaceManager.getPartNum(pageNum) == 0) return -1L;

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new FreePageLogRecord(transNum, pageNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        dirtyPageTable.remove(pageNum);
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Creates a savepoint for a transaction. Creating a savepoint with
     * the same name as an existing savepoint for the transaction should
     * delete the old savepoint.
     *
     * The appropriate LSN should be recorded so that a partial rollback
     * is possible later.
     *
     * @param transNum transaction to make savepoint for
     * @param name name of savepoint
     */
    @Override
    public void savepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);
        transactionEntry.addSavepoint(name);
    }

    /**
     * Releases (deletes) a savepoint for a transaction.
     * @param transNum transaction to delete savepoint for
     * @param name name of savepoint
     */
    @Override
    public void releaseSavepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);
        transactionEntry.deleteSavepoint(name);
    }

    /**
     * Rolls back transaction to a savepoint.
     *
     * All changes done by the transaction since the savepoint should be undone,
     * in reverse order, with the appropriate CLRs written to log. The transaction
     * status should remain unchanged.
     *
     * @param transNum transaction to partially rollback
     * @param name name of savepoint
     */
    @Override
    public void rollbackToSavepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        // All of the transaction's changes strictly after the record at LSN should be undone.
        long savepointLSN = transactionEntry.getSavepoint(name);

        // TODO(proj5): implement
        rollbackToLSN(transNum, savepointLSN);
        return;
    }

    /**
     * Create a checkpoint.
     *
     * First, a begin checkpoint record should be written.
     *
     * Then, end checkpoint records should be filled up as much as possible first
     * using recLSNs from the DPT, then status/lastLSNs from the transactions
     * table, and written when full (or when nothing is left to be written).
     * You may find the method EndCheckpointLogRecord#fitsInOneRecord here to
     * figure out when to write an end checkpoint record.
     *
     * Finally, the master record should be rewritten with the LSN of the
     * begin checkpoint record.
     */
    @Override
    public synchronized void checkpoint() {
        // Create begin checkpoint log record and write to log
        LogRecord beginRecord = new BeginCheckpointLogRecord();
        long beginLSN = logManager.appendToLog(beginRecord);

        Map<Long, Long> checkPointDPT = new HashMap<>();
        Map<Long, Pair<Transaction.Status, Long>> checkPointTxnTable = new HashMap<>();

        // TODO(proj5): generate end checkpoint record(s) for DPT and transaction table

        // iterate through the dirtyPageTable and copy the entries.
        // If at any point, copying the current record would cause
        // the end checkpoint record to be too large, an end checkpoint
        // record with the copied DPT entries should be appended to the log.

        for (Long pageNum: this.dirtyPageTable.keySet()) {
            if (!EndCheckpointLogRecord.fitsInOneRecord(checkPointDPT.size() + 1, checkPointTxnTable.size())) {
                this.logManager.appendToLog(new EndCheckpointLogRecord(checkPointDPT, checkPointTxnTable));
                checkPointDPT.clear();
                checkPointTxnTable.clear();
            }
            checkPointDPT.put(pageNum, this.dirtyPageTable.get(pageNum));
        }

        // iterate through the transaction table, and copy the status/lastLSN,
        // outputting end checkpoint records only as needed.
        for (Long transNum: this.transactionTable.keySet()) {
            if (!EndCheckpointLogRecord.fitsInOneRecord(checkPointDPT.size(), checkPointTxnTable.size() + 1)) {
                this.logManager.appendToLog(new EndCheckpointLogRecord(checkPointDPT, checkPointTxnTable));
                checkPointDPT.clear();
                checkPointTxnTable.clear();
            }
            Transaction.Status curStatus = this.transactionTable.get(transNum).transaction.getStatus();
            Long lastLSN = this.transactionTable.get(transNum).lastLSN;
            checkPointTxnTable.put(transNum, new Pair<>(curStatus, lastLSN));
        }

        // Last end checkpoint record
        LogRecord endRecord = new EndCheckpointLogRecord(checkPointDPT, checkPointTxnTable);
        this.logManager.appendToLog(endRecord);
        // Ensure checkpoint is fully flushed before updating the master record
        flushToLSN(endRecord.getLSN());

        // Update master record
        MasterLogRecord masterRecord = new MasterLogRecord(beginLSN);
        this.logManager.rewriteMasterRecord(masterRecord);
    }
    /**
     * Flushes the log to at least the specified record,
     * essentially flushing up to and including the page
     * that contains the record specified by the LSN.
     *
     * @param LSN LSN up to which the log should be flushed
     */
    @Override
    public void flushToLSN(long LSN) {
        this.logManager.flushToLSN(LSN);
    }

    @Override
    public void dirtyPage(long pageNum, long LSN) {
        dirtyPageTable.putIfAbsent(pageNum, LSN);
        // Handle race condition where earlier log is beaten to the insertion by
        // a later log.
        dirtyPageTable.computeIfPresent(pageNum, (k, v) -> Math.min(LSN,v));
    }

    @Override
    public void close() {
        this.checkpoint();
        this.logManager.close();
    }

    // Restart Recovery ////////////////////////////////////////////////////////

    /**
     * Called whenever the database starts up, and performs restart recovery.
     * Recovery is complete when the Runnable returned is run to termination.
     * New transactions may be started once this method returns.
     *
     * This should perform the three phases of recovery, and also clean the
     * dirty page table of non-dirty pages (pages that aren't dirty in the
     * buffer manager) between redo and undo, and perform a checkpoint after
     * undo.
     */
    @Override
    public void restart() {
        this.restartAnalysis();
        this.restartRedo();
        this.redoComplete = true;
        this.cleanDPT();
        this.restartUndo();
        this.checkpoint();
    }

    /**
     * This method performs the analysis pass of restart recovery.
     *
     * First, the master record should be read (LSN 0). The master record contains
     * one piece of information: the LSN of the last successful checkpoint.
     *
     * We then begin scanning log records, starting at the beginning of the
     * last successful checkpoint.
     *
     * If the log record is for a transaction operation (getTransNum is present)
     * - update the transaction table
     *
     * If the log record is page-related (getPageNum is present), update the dpt
     *   - update/undoupdate page will dirty pages
     *   - free/undoalloc page always flush changes to disk
     *   - no action needed for alloc/undofree page
     *
     * If the log record is for a change in transaction status:
     * - update transaction status to COMMITTING/RECOVERY_ABORTING/COMPLETE
     * - update the transaction table
     * - if END_TRANSACTION: clean up transaction (Transaction#cleanup), remove
     *   from txn table, and add to endedTransactions
     *
     * If the log record is an end_checkpoint record:
     * - Copy all entries of checkpoint DPT (replace existing entries if any)
     * - Skip txn table entries for transactions that have already ended
     * - Add to transaction table if not already present
     * - Update lastLSN to be the larger of the existing entry's (if any) and
     *   the checkpoint's
     * - The status's in the transaction table should be updated if it is possible
     *   to transition from the status in the table to the status in the
     *   checkpoint. For example, running -> aborting is a possible transition,
     *   but aborting -> running is not.
     *
     * After all records in the log are processed, for each table entry:
     *  - if COMMITTING: clean up the transaction, change status to COMPLETE,
     *    remove from the ttable, and append an end record
     *  - if RUNNING: change status to RECOVERY_ABORTING, and append an abort
     *    record
     *  - if RECOVERY_ABORTING: no action needed
     */
    void restartAnalysis() {
        // Read master record
        LogRecord record = logManager.fetchLogRecord(0L);
        // Type checking
        assert (record != null && record.getType() == LogType.MASTER);
        MasterLogRecord masterRecord = (MasterLogRecord) record;
        // Get start checkpoint LSN
        long LSN = masterRecord.lastCheckpointLSN;
        // Set of transactions that have completed
        Set<Long> endedTransactions = new HashSet<>();
        // TODO(proj5): implement

        Iterator<LogRecord> it = logManager.scanFrom(LSN);
        while (it.hasNext()) {
            LogRecord cur = it.next();
            Long transNum = null;
            Transaction transaction = null;
            /* if transaction operation */
            if (cur.getTransNum().isPresent()) {
                transNum = cur.getTransNum().get();

                if (!transactionTable.containsKey(transNum)) {
                    this.startTransaction(newTransaction.apply(transNum));
                }
                this.transactionTable.get(transNum).lastLSN = cur.getLSN();
                // transaction = transactionTable.get(transNum).transaction;

            }

            /* if page operation */
            if (cur.getPageNum().isPresent()) {
                if (transNum == null) return;
                Long pageNum = cur.getPageNum().get();
                if (cur.type == LogType.UPDATE_PAGE || cur.type == LogType.UNDO_UPDATE_PAGE) {
                    dirtyPage(pageNum, cur.getLSN());
                } else if (cur.type == LogType.FREE_PAGE || cur.type == LogType.UNDO_ALLOC_PAGE) {
                    flushToLSN(cur.getLSN());
                    this.dirtyPageTable.remove(pageNum);
                } else if (cur.type != LogType.ALLOC_PAGE && cur.type != LogType.UNDO_FREE_PAGE) {
                    // no such page operation trans type
                    return;
                } // if type == alloc or undo_free, no action needed
            }

            /* if change in transaction status */
            if (cur.getTransNum().isPresent()) {
                transaction = transactionTable.get(transNum).transaction;
                if (cur.type == LogType.COMMIT_TRANSACTION) {
                    transaction.setStatus(Transaction.Status.COMMITTING);
                } else if (cur.type == LogType.ABORT_TRANSACTION) {
                    transaction.setStatus(Transaction.Status.RECOVERY_ABORTING);
                } else if (cur.type == LogType.END_TRANSACTION) {
                    // clean up transaction
                    transaction.cleanup();
                    // set transaction status
                    transaction.setStatus(Transaction.Status.COMPLETE);
                    // delete from transactionTable
                    transactionTable.remove(transNum);
                    // add transaction to endedTransaction
                    endedTransactions.add(transNum);
                } else {
                    if (transaction.getStatus() == Transaction.Status.COMMITTING) {
                        transaction.setStatus(Transaction.Status.COMPLETE);
                    } else if (transaction.getStatus() == Transaction.Status.RECOVERY_ABORTING) {
                        transaction.setStatus(Transaction.Status.COMPLETE);
                    }
                }
            }

            /* if end_checkpoint status */
            if (cur.type == LogType.END_CHECKPOINT) {
                // Copy all entries of checkpoint DPT (replace existing entries if any)
                Map<Long, Long> cpDpt = cur.getDirtyPageTable();
                // for each entry in the checkpoint's snapshot of the dirty page table
                for (Long updateTrans : cpDpt.keySet()) {
                    // the recLSN of a page in the checkpoint should always be used
                    this.dirtyPageTable.put(updateTrans, cpDpt.get(updateTrans));
                }

                Map<Long, Pair<Transaction.Status, Long>> cpTxnTable = cur.getTransactionTable();
                // For each entry in the checkpoint's snapshot of the transaction table
                for (Long updateTrans : cpTxnTable.keySet()) {
                    Transaction.Status transStatus = cpTxnTable.get(updateTrans).getFirst();
                    Long prevLSN = cpTxnTable.get(updateTrans).getSecond();

                    if (!endedTransactions.contains(updateTrans)) {
                        // Skip txn table entries for transactions that have already ended
                        if (!this.transactionTable.containsKey(updateTrans)) {
                            // Add to transaction table if not already present
                            this.startTransaction(newTransaction.apply(updateTrans));
                            this.transactionTable.get(updateTrans).lastLSN = prevLSN;
                        } else {
                            // Update lastLSN to be the larger of the existing entry's (if any) and the checkpoint's
                            this.transactionTable.get(updateTrans).lastLSN = Math.max(this.transactionTable.get(updateTrans).lastLSN, prevLSN);
                        }
                        // The status's in the transaction table should be updated if it is possible
                        // to transition from the status in the table to the status in the checkpoint
                        Transaction update = transactionTable.get(updateTrans).transaction;
                        update.setStatus(updateCPStatus(transStatus, update.getStatus()));
                    }
                }
            }
        }

        // The transaction table at this point should have transactions that are
        // in one of the following states: RUNNING, COMMITTING, or RECOVERY_ABORTING
        for (Long txnTableTransNum : this.transactionTable.keySet()) {
            TransactionTableEntry entry = this.transactionTable.get(txnTableTransNum);
            Transaction trans = entry.transaction;
            Transaction.Status transStatus = trans.getStatus();

            if (transStatus == Transaction.Status.COMMITTING) {
                // All transactions in the COMMITTING state should be ended
                trans.cleanup();
                trans.setStatus(Transaction.Status.COMPLETE);
                this.logManager.appendToLog(new EndTransactionLogRecord(txnTableTransNum, entry.lastLSN));
                this.transactionTable.remove(txnTableTransNum);
            } else if (transStatus == Transaction.Status.RUNNING) {
                // All transactions in the RUNNING state should be moved into the RECOVERY_ABORTING
                trans.setStatus(Transaction.Status.RECOVERY_ABORTING);
                entry.lastLSN = this.logManager.appendToLog(new AbortTransactionLogRecord(txnTableTransNum, entry.lastLSN));
            } // Nothing needs to be done for transactions in the RECOVERY_ABORTING state
        }
    }

    private Transaction.Status updateCPStatus(Transaction.Status cpStatus, Transaction.Status curStatus) {
        if (cpStatus == Transaction.Status.RUNNING) {
            if (curStatus == Transaction.Status.ABORTING) {
                // then curStatus is more advanced
                return Transaction.Status.RECOVERY_ABORTING;
            } else { // curStatus is running, committing or complete
                return curStatus;
            }
        } else if (cpStatus == Transaction.Status.ABORTING) {
            // if curStatus is less advanced
            if (curStatus == Transaction.Status.RUNNING
                    || curStatus == Transaction.Status.ABORTING) {
                return Transaction.Status.RECOVERY_ABORTING;
            } else { // then curStatus is more advanced
                return curStatus;
            }
        } else if (cpStatus == Transaction.Status.COMMITTING) {
            if (curStatus == Transaction.Status.RUNNING
                    || curStatus == Transaction.Status.COMMITTING) {
                // then cpStatus is more advanced
                return cpStatus;
            } else {
                // then curStatus is complete
                return curStatus;
            }
        } else { // cpStatus == Transaction.Status.COMPLETE
            if (curStatus != Transaction.Status.COMPLETE) {
                // then cpStatus is more advanced
                return cpStatus;
            } else {
                // then curStatus is complete, so most advanced
                return curStatus;
            }
        }
    }


    /**
     * This method performs the redo pass of restart recovery.
     *
     * First, determine the starting point for REDO from the dirty page table.
     *
     * Then, scanning from the starting point, if the record is redoable and
     * - partition-related (Alloc/Free/UndoAlloc/UndoFree..Part), always redo it
     * - allocates a page (AllocPage/UndoFreePage), always redo it
     * - modifies a page (Update/UndoUpdate/Free/UndoAlloc....Page) in
     *   the dirty page table with LSN >= recLSN, the page is fetched from disk,
     *   the pageLSN is checked, and the record is redone if needed.
     */
    void restartRedo() {
        // TODO(proj5): implement

        // check if dpt is empty
        if (this.dirtyPageTable == null || this.dirtyPageTable.isEmpty()) {
            return;
        }

        // find the lowest recLSN in dpt
        Long minRecLSN = (long)1000000000;
        for (Map.Entry<Long, Long> entry: this.dirtyPageTable.entrySet()) {
            if (entry.getValue() < minRecLSN) {
                minRecLSN = entry.getValue();
            }
        }

        Iterator<LogRecord> it = logManager.scanFrom(minRecLSN);
        while (it.hasNext()) {
            LogRecord cur = it.next();
            if (!cur.isRedoable()) {
                continue;
            }

            // the log record is redoable
            if (cur.type == LogType.ALLOC_PART || cur.type == LogType.UNDO_ALLOC_PART
                    || cur.type == LogType.FREE_PART || cur.type == LogType.UNDO_FREE_PART
                    || cur.type == LogType.ALLOC_PAGE || cur.type == LogType.UNDO_FREE_PAGE) {
                cur.redo(this, diskSpaceManager, bufferManager);
            } else if (cur.type == LogType.UPDATE_PAGE || cur.type == LogType.UNDO_UPDATE_PAGE
                    || cur.type == LogType.FREE_PAGE || cur.type == LogType.UNDO_ALLOC_PAGE) {
                Long pageNum = cur.getPageNum().get();
                Page page = bufferManager.fetchPage(new DummyLockContext(), pageNum);
                try {
                    // Do anything that requires the page here
                    Long pageLSN = page.getPageLSN();
                    if (this.dirtyPageTable.keySet().contains(pageNum)
                            && cur.getLSN() >= this.dirtyPageTable.get(pageNum)
                            && pageLSN < cur.getLSN()) {
                        cur.redo(this, diskSpaceManager, bufferManager);
                    }
                } finally {
                    page.unpin();
                }
            }
        }
    }

    /**
     * This method performs the undo pass of restart recovery.

     * First, a priority queue is created sorted on lastLSN of all aborting
     * transactions.
     *
     * Then, always working on the largest LSN in the priority queue until we are done,
     * - if the record is undoable, undo it, and append the appropriate CLR
     * - replace the entry with a new one, using the undoNextLSN if available,
     *   if the prevLSN otherwise.
     * - if the new LSN is 0, clean up the transaction, set the status to complete,
     *   and remove from transaction table.
     */
    void restartUndo() {
        // TODO(proj5): implement

        // build the pq of the lastLSNs of all aborting transactions (status == RECOVERY_ABORTING)
        PriorityQueue<Pair<Long, Transaction>> lastLSNs = new PriorityQueue<>(new PairFirstReverseComparator<>());
        for (Long transNum : this.transactionTable.keySet()) {
            TransactionTableEntry entry = this.transactionTable.get(transNum);
            if (entry.transaction.getStatus() == Transaction.Status.RECOVERY_ABORTING) {
                lastLSNs.add(new Pair<>(entry.lastLSN, entry.transaction));
            }
        }

        while (!lastLSNs.isEmpty()) {
            Pair<Long, Transaction> largestPair = lastLSNs.poll();
            Long largestLSN = largestPair.getFirst();
            LogRecord logRecord = logManager.fetchLogRecord(largestLSN);
            Long transactionNum = logRecord.getTransNum().get();
            TransactionTableEntry toUpdateTrans = this.transactionTable.get(transactionNum);

            // if the record is undoable, we write the CLR out and undo it*
            if (logRecord.isUndoable()) {
                LogRecord clr = logRecord.undo(toUpdateTrans.lastLSN);
                Long clrLSN = this.logManager.appendToLog(clr);

                // update entry in transaction table
                toUpdateTrans.lastLSN = clrLSN;
                // redo the clr
                clr.redo(this, this.diskSpaceManager, this.bufferManager);
            }
            // replace the LSN in the set with the undoNextLSN of the record if it has one,
            // or the prevLSN otherwise;
            Long newLSN = 0L;
            if (logRecord.getUndoNextLSN().isPresent()) {
                newLSN = logRecord.getUndoNextLSN().get();
            } else {
                newLSN = logRecord.getPrevLSN().get();
            }
            if (newLSN == 0L) {
                this.logManager.appendToLog(new EndTransactionLogRecord(transactionNum, toUpdateTrans.lastLSN));
                toUpdateTrans.transaction.setStatus(Transaction.Status.COMPLETE);
                this.transactionTable.remove(transactionNum);
            } else {
                lastLSNs.add(new Pair(newLSN, largestPair.getSecond()));
            }
        }
    }

    /**
     * Removes pages from the DPT that are not dirty in the buffer manager.
     * This is slow and should only be used during recovery.
     */
    void cleanDPT() {
        Set<Long> dirtyPages = new HashSet<>();
        bufferManager.iterPageNums((pageNum, dirty) -> {
            if (dirty) dirtyPages.add(pageNum);
        });
        Map<Long, Long> oldDPT = new HashMap<>(dirtyPageTable);
        dirtyPageTable.clear();
        for (long pageNum : dirtyPages) {
            if (oldDPT.containsKey(pageNum)) {
                dirtyPageTable.put(pageNum, oldDPT.get(pageNum));
            }
        }
    }

    // Helpers /////////////////////////////////////////////////////////////////
    /**
     * Comparator for Pair<A, B> comparing only on the first element (type A),
     * in reverse order.
     */
    private static class PairFirstReverseComparator<A extends Comparable<A>, B> implements
            Comparator<Pair<A, B>> {
        @Override
        public int compare(Pair<A, B> p0, Pair<A, B> p1) {
            return p1.getFirst().compareTo(p0.getFirst());
        }
    }
}