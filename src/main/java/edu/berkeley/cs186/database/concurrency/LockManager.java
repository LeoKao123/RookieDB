package edu.berkeley.cs186.database.concurrency;

import edu.berkeley.cs186.database.TransactionContext;

import java.util.*;

/**
 * LockManager maintains the bookkeeping for what transactions have what locks
 * on what resources and handles queuing logic. The lock manager should generally
 * NOT be used directly: instead, code should call methods of LockContext to
 * acquire/release/promote/escalate locks.
 *
 * The LockManager is primarily concerned with the mappings between
 * transactions, resources, and locks, and does not concern itself with multiple
 * levels of granularity. Multigranularity is handled by LockContext instead.
 *
 * Each resource the lock manager manages has its own queue of LockRequest
 * objects representing a request to acquire (or promote/acquire-and-release) a
 * lock that could not be satisfied at the time. This queue should be processed
 * every time a lock on that resource gets released, starting from the first
 * request, and going in order until a request cannot be satisfied. Requests
 * taken off the queue should be treated as if that transaction had made the
 * request right after the resource was released in absence of a queue (i.e.
 * removing a request by T1 to acquire X(db) should be treated as if T1 had just
 * requested X(db) and there were no queue on db: T1 should be given the X lock
 * on db, and put in an unblocked state via Transaction#unblock).
 *
 * This does mean that in the case of:
 *    queue: S(A) X(A) S(A)
 * only the first request should be removed from the queue when the queue is
 * processed.
 */
public class LockManager {
    // transactionLocks is a mapping from transaction number to a list of lock
    // objects held by that transaction.
    private Map<Long, List<Lock>> transactionLocks = new HashMap<>();

    // resourceEntries is a mapping from resource names to a ResourceEntry
    // object, which contains a list of Locks on the object, as well as a
    // queue for requests on that resource.
    private Map<ResourceName, ResourceEntry> resourceEntries = new HashMap<>();

    /*
    transactionLocks: { 1 => [ X(db) ] } (transaction 1 has 1 lock: X(db))
    resourceEntries: { db => { locks: [ {1, X(db)} ], queue: [] } }
    (there is 1 lock on db: an X lock by transaction 1, nothing on the queue)
     */

    // A ResourceEntry contains the list of locks on a resource, as well as
    // the queue for requests for locks on the resource.
    private class ResourceEntry {
        // List of currently granted locks on the resource.
        List<Lock> locks = new ArrayList<>();
        // Queue for yet-to-be-satisfied lock requests on this resource.
        Deque<LockRequest> waitingQueue = new ArrayDeque<>();

        // Below are a list of helper methods we suggest you implement.
        // You're free to modify their type signatures, delete, or ignore them.

        /**
         * Check if `lockType` is compatible with preexisting locks. Allows
         * conflicts for locks held by transaction with id `except`, which is
         * useful when a transaction tries to replace a lock it already has on
         * the resource.
         */
        public boolean checkCompatible(LockType lockType, long except) {
            // TODO(proj4_part1): implement
            for (Lock lock: locks) {
                if (lock.transactionNum != except && !LockType.compatible(lock.lockType, lockType)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Gives the transaction the lock `lock`. Assumes that the lock is
         * compatible. Updates lock on resource if the transaction already has a
         * lock.
         */
        public void grantOrUpdateLock(Lock lock) {
            // TODO(proj4_part1): implement (remove transaction locks)
            // used by processQueue, acquire, acquireAndRelease and promote

            // first check if this.locks already has a lock held by the transaction
            Lock holdingLock = currentlyHoldingLock(lock);
            if (holdingLock == null) { // granting a new lock
                // add the new lock
                this.locks.add(lock);
                // update the transaction locks
                if (LockManager.this.transactionLocks.containsKey(lock.transactionNum)) {
                    LockManager.this.transactionLocks.get(lock.transactionNum).add(lock);
                } else {
                    LockManager.this.transactionLocks.put(lock.transactionNum, new ArrayList<>());
                    LockManager.this.transactionLocks.get(lock.transactionNum).add(lock);
                }
            } else { // updating the existing lock
                holdingLock.lockType = lock.lockType;
            }
        }

        private Lock currentlyHoldingLock(Lock lock) {
            for (Lock checkLock: this.locks) {
                if (checkLock.transactionNum == lock.transactionNum) {
                    return checkLock;
                }
            }
            return null;
        }
        /**
         * Releases the lock `lock` and processes the queue. Assumes that the
         * lock has been granted before.
         */
        public void releaseLock(Lock lock) {
            // TODO(proj4_part1): implement
            if (this.locks == null || this.locks.isEmpty()) {
                return;
            }
            // release the lock
            this.locks.remove(lock);
            // update transaction lock
            LockManager.this.transactionLocks.get(lock.transactionNum).remove(lock);
            if (LockManager.this.transactionLocks.get(lock.transactionNum).isEmpty()) {
                LockManager.this.transactionLocks.remove(lock.transactionNum);
            }
            // processes the queue
            processQueue();
        }

        /**
         * Adds `request` to the front of the queue if addFront is true, or to
         * the end otherwise.
         */
        public void addToQueue(LockRequest request, boolean addFront) {
            // TODO(proj4_part1): implement
            if (addFront) {
                this.waitingQueue.addFirst(request);
            } else {
                this.waitingQueue.add(request);
            }
        }

        /**
         * Grant locks to requests from front to back of the queue, stopping
         * when the next lock cannot be granted. Once a request is completely
         * granted, the transaction that made the request can be unblocked.
         */
        private void processQueue() {
            Iterator<LockRequest> requests = waitingQueue.iterator();
            // TODO(proj4_part1): implement
            // The queue for each resource is processed independently of other queues
            while (requests.hasNext()) {
                // first consider the request at the front of the queue
                LockRequest request = requests.next();

                // check if the resource is releasing a lock that the same transaction is currently holding
                // if yes, then set the exception
                long currentTransNum = request.transaction.getTransNum();
                long exception = -1;
                for (Lock acquiredLock: request.releasedLocks) {
                    if (acquiredLock.transactionNum == currentTransNum) {
                        exception = currentTransNum;
                        break;
                    }
                }
                // should be repeated until the first request on the queue cannot be satisfied
                if (!checkCompatible(request.lock.lockType, exception)) {
                    return;
                }
                // remove from the queue
                this.waitingQueue.remove(request);
                // any locks that the request stated should be released are released
                // update the transaction lock
                for (Lock toRelease : request.releasedLocks) {
                    LockManager.this.transactionLocks.get(toRelease.transactionNum).remove(toRelease);
                    if (LockManager.this.transactionLocks.get(toRelease.transactionNum).isEmpty()) {
                        LockManager.this.transactionLocks.remove(toRelease.transactionNum);
                    }

                    //update the resource's locks
                    ResourceEntry releaseLockFrom = getResourceEntry(toRelease.name);
                    releaseLockFrom.locks.remove(toRelease);
                }
                // the transaction that made the request should be given the lock
                this.grantOrUpdateLock(request.lock);
                // the transaction that made the request should be unblocked
                request.transaction.unblock();
            }
        }

        /**
         * Gets the type of lock `transaction` has on this resource.
         */
        public LockType getTransactionLockType(long transaction) {
            // TODO(proj4_part1): implement check!!
            for (Lock lock : this.locks) {
                if (lock.transactionNum == transaction) {
                    return lock.lockType;
                }
            }
            return LockType.NL;
        }

        @Override
        public String toString() {
            return "Active Locks: " + Arrays.toString(this.locks.toArray()) +
                    ", Queue: " + Arrays.toString(this.waitingQueue.toArray());
        }
    }

    // You should not modify or use this directly.
    private Map<String, LockContext> contexts = new HashMap<>();

    /**
     * Helper method to fetch the resourceEntry corresponding to `name`.
     * Inserts a new (empty) resourceEntry into the map if no entry exists yet.
     */
    private ResourceEntry getResourceEntry(ResourceName name) {
        resourceEntries.putIfAbsent(name, new ResourceEntry());
        return resourceEntries.get(name);
    }

    /**
     * Acquire a `lockType` lock on `name`, for transaction `transaction`, and
     * releases all locks on `releaseNames` held by the transaction after
     * acquiring the lock in one atomic action.
     *
     * Error checking must be done before any locks are acquired or released. If
     * the new lock is not compatible with another transaction's lock on the
     * resource, the transaction is blocked and the request is placed at the
     * FRONT of the resource's queue.
     *
     * Locks on `releaseNames` should be released only after the requested lock
     * has been acquired. The corresponding queues should be processed.
     *
     * An acquire-and-release that releases an old lock on `name` should NOT
     * change the acquisition time of the lock on `name`, i.e. if a transaction
     * acquired locks in the order: S(A), X(B), acquire X(A) and release S(A),
     * the lock on A is considered to have been acquired before the lock on B.
     *
     * @throws DuplicateLockRequestException if a lock on `name` is already held
     * by `transaction` and isn't being released
     * @throws NoLockHeldException if `transaction` doesn't hold a lock on one
     * or more of the names in `releaseNames`
     */
    public void acquireAndRelease(TransactionContext transaction, ResourceName name,
                                  LockType lockType, List<ResourceName> releaseNames)
            throws DuplicateLockRequestException, NoLockHeldException {
        // TODO(proj4_part1): implement
        // You may modify any part of this method. You are not required to keep
        // all your code within the given synchronized block and are allowed to
        // move the synchronized block elsewhere if you wish.

        boolean shouldBlock = false;
        synchronized (this) {
            ResourceEntry entry = getResourceEntry(name);

            // first, check duplicate exception
            if (entry.getTransactionLockType(transaction.getTransNum()) != LockType.NL && !releaseNames.contains(name)) {
                throw (new DuplicateLockRequestException("Current transaction is already holding a lock on the resource"));
            }

            Lock newLock = new Lock(name, lockType, transaction.getTransNum());
            LockRequest newRequest = new LockRequest(transaction, newLock);

            for (ResourceName resourceName: releaseNames) {
                if (releaseNames.equals(newLock.name)) {
                    continue;
                }
            }

            if (!entry.checkCompatible(lockType, transaction.getTransNum()) || !entry.waitingQueue.isEmpty()) {
                // check if the request lockType is compatible to the granted locks
                // also checks if the request lockType is held by another transaction

                //use addToQueue helper function here
                newRequest.releasedLocks = new ArrayList<>();
                List<Lock> transLocks = getLocks(transaction);
                for (ResourceName resourceName: releaseNames) {
                    for (Lock lock: transLocks) {
                        if (lock.name == resourceName) {
                            newRequest.releasedLocks.add(lock);
                            break;
                        }
                    }
                }
                entry.addToQueue(newRequest, true); //pushes to the front if the queue
                System.out.println(entry.waitingQueue);
                System.out.println(newRequest);
                entry.processQueue();
                System.out.println(entry.waitingQueue);
                shouldBlock = true;
            } else {
                // ready to grant the lock to the transaction
                entry.grantOrUpdateLock(newLock);

                // now release resources in releaseNames
                List<Lock> curTransLocks = this.getLocks(transaction);
                ResourceEntry removeLockFrom = null;
                for (ResourceName releaseName : releaseNames) {
                    Lock toRemove = null;

                    if (releaseNames.equals(newLock.name)) {
                        continue;
                    }

                    for (Lock lock : curTransLocks) {
                        if (lock.name.equals(releaseName)) {
                            if (!releaseName.equals(newLock.name)) {
                                toRemove = lock;
                                removeLockFrom = getResourceEntry(releaseName);
                            }
                        }
                    }
                    if (toRemove == null && !releaseName.equals(newLock.name)) {
                        throw (new NoLockHeldException("Transaction has not acquired a lock on this resource"));
                    }
                    if (removeLockFrom != null) {
                        removeLockFrom.releaseLock(toRemove);
                    }
                }
            }
        }
        if (shouldBlock) {
            transaction.prepareBlock();
            transaction.block();
        }
    }

    /**
     * Acquire a `lockType` lock on `name`, for transaction `transaction`.
     *
     * Error checking must be done before the lock is acquired. If the new lock
     * is not compatible with another transaction's lock on the resource, or if there are
     * other transaction in queue for the resource, the transaction is
     * blocked and the request is placed at the **back** of NAME's queue.
     *
     * @throws DuplicateLockRequestException if a lock on `name` is held by
     * `transaction`
     */
    public void acquire(TransactionContext transaction, ResourceName name,
                        LockType lockType) throws DuplicateLockRequestException {
        // TODO(proj4_part1): implement
        // You may modify any part of this method. You are not required to keep all your
        // code within the given synchronized block and are allowed to move the
        // synchronized block elsewhere if you wish.

        /*idea:
         * attempt to acquire the lock:
         *   check duplicate: if the transaction is already holding a lock on the resource, exception
         *   if lock is currently held by another transaction: create a new request and add it to the back of the queue
         *   else: create a new Lock; add the Lock to "locks" list; add the entry to transactionlocks;
         **/

        boolean shouldBlock = false;
        synchronized (this) {
            ResourceEntry entry = getResourceEntry(name);
            // first, check duplicate exception
            if (entry.getTransactionLockType(transaction.getTransNum()) != LockType.NL) {
                throw (new DuplicateLockRequestException("Current transaction is already holding a lock on the resource"));
            }

            Lock newLock = new Lock(name, lockType, transaction.getTransNum());
            // check if the request lockType is compatible to the granted locks
            // also checks if the request lockType is held by another transaction
            if (!entry.checkCompatible(lockType, -1) || !entry.waitingQueue.isEmpty()) {
                //use addToQueue helper function here
                LockRequest newRequest = new LockRequest(transaction, newLock);
                entry.addToQueue(newRequest, false);//pushes to the BACK of the queue
                shouldBlock = true;
            } else {
                // ready to grant the lock to the transaction
                entry.grantOrUpdateLock(newLock);
            }
        }
        if (shouldBlock) {
            transaction.prepareBlock();
            transaction.block();
        }
    }

    /**
     * Release `transaction`'s lock on `name`. Error checking must be done
     * before the lock is released.
     *
     * The resource name's queue should be processed after this call. If any
     * requests in the queue have locks to be released, those should be
     * released, and the corresponding queues also processed.
     *
     * @throws NoLockHeldException if no lock on `name` is held by `transaction`
     */
    public void release(TransactionContext transaction, ResourceName name)
            throws NoLockHeldException {
        // TODO(proj4_part1): implement
        // You may modify any part of this method.
        synchronized (this) {
            /*idea:
             * if no lock on name is held by transaction, exception
             * remove lock from locks list; remove lock from transactionLocks entry
             * */
            List<Lock> curTransLocks = this.getLocks(transaction);
            Lock toRemove = null;
            for (Lock lock: curTransLocks) {
                if (lock.name.equals(name)) {
                    toRemove = lock;
                }
            }
            if (toRemove == null) {
                throw(new NoLockHeldException("Transaction has not acquired a lock on this resource"));
            }

            ResourceEntry entry = getResourceEntry(name);
            entry.releaseLock(toRemove); //release lock releases the lock and also process the queue
        }
    }

    /**
     * Promote a transaction's lock on `name` to `newLockType` (i.e. change
     * the transaction's lock on `name` from the current lock type to
     * `newLockType`, if its a valid substitution).
     *
     * Error checking must be done before any locks are changed. If the new lock
     * is not compatible with another transaction's lock on the resource, the
     * transaction is blocked and the request is placed at the FRONT of the
     * resource's queue.
     *
     * A lock promotion should NOT change the acquisition time of the lock, i.e.
     * if a transaction acquired locks in the order: S(A), X(B), promote X(A),
     * the lock on A is considered to have been acquired before the lock on B.
     *
     * @throws DuplicateLockRequestException if `transaction` already has a
     * `newLockType` lock on `name`
     * @throws NoLockHeldException if `transaction` has no lock on `name`
     * @throws InvalidLockException if the requested lock type is not a
     * promotion. A promotion from lock type A to lock type B is valid if and
     * only if B is substitutable for A, and B is not equal to A.
     */
    public void promote(TransactionContext transaction, ResourceName name,
                        LockType newLockType)
            throws DuplicateLockRequestException, NoLockHeldException, InvalidLockException {
        // TODO(proj4_part1): implement
        // You may modify any part of this method.
        boolean shouldBlock = false;
        synchronized (this) {
            // exception check: DuplicateLockRequestException
            ResourceEntry entry = getResourceEntry(name);
            if (entry.getTransactionLockType(transaction.getTransNum()) == newLockType) {
                throw (new DuplicateLockRequestException("Current transaction is already holding a lock of newLockType on the resource"));
            }

            List<Lock> curTransLocks = this.getLocks(transaction);
            Lock toPromote = null;
            for (Lock lock: curTransLocks) {
                if (lock.name.equals(name)) {
                    toPromote = lock;
                }
            }
            // exception check: NoLockRequestException
            if (toPromote == null) {
                throw(new NoLockHeldException("Transaction has not acquired a lock on this resource"));
            }
            // exception check: InvalidLockRequestException
            if (!LockType.substitutable(newLockType, toPromote.lockType)) {
                throw(new InvalidLockException("New lock type is not a promotion for the original lockType"));
            }

            // check if the request lockType is compatible to the granted locks
            // also checks if the request lockType is held by another transaction
            if (!entry.checkCompatible(newLockType, transaction.getTransNum())) {
                Lock newLock = new Lock(name, newLockType, transaction.getTransNum());
                LockRequest newRequest = new LockRequest(transaction, newLock);
                newRequest.releasedLocks = new ArrayList<>();
                newRequest.releasedLocks.add(toPromote);
                entry.addToQueue(newRequest, true);//pushes to the FRONT of the queue
                shouldBlock = true;
            } else {
                if (newLockType == LockType.SIX) { // taken care using acquireAndRelease
                    List<ResourceName> toReleaseLocks = new ArrayList<>();
                    toReleaseLocks.add(name);
                    acquireAndRelease(transaction, name, newLockType, toReleaseLocks); // what to put as releaseList??
                } else {
                    release(transaction, name);
                    acquire(transaction, name, newLockType);
                }
            }

        }
        if (shouldBlock) {
            transaction.prepareBlock();
            transaction.block();
        }
    }


    /**
     * Return the type of lock `transaction` has on `name` or NL if no lock is
     * held.
     */
    public synchronized LockType getLockType(TransactionContext transaction, ResourceName name) {
        // TODO(proj4_part1): implement
        ResourceEntry resourceEntry = getResourceEntry(name);
        if (resourceEntry.locks.isEmpty()) {
            return LockType.NL;
        } else {
            return resourceEntry.getTransactionLockType(transaction.getTransNum());
        }
    }

    /**
     * Returns the list of locks held on `name`, in order of acquisition.
     */
    public synchronized List<Lock> getLocks(ResourceName name) {
        return new ArrayList<>(resourceEntries.getOrDefault(name, new ResourceEntry()).locks);
    }

    /**
     * Returns the list of locks held by `transaction`, in order of acquisition.
     */
    public synchronized List<Lock> getLocks(TransactionContext transaction) {
        return new ArrayList<>(transactionLocks.getOrDefault(transaction.getTransNum(),
                Collections.emptyList()));
    }

    /**
     * Creates a lock context. See comments at the top of this file and the top
     * of LockContext.java for more information.
     */
    public synchronized LockContext context(String name) {
        if (!contexts.containsKey(name)) {
            contexts.put(name, new LockContext(this, null, name));
        }
        return contexts.get(name);
    }

    /**
     * Create a lock context for the database. See comments at the top of this
     * file and the top of LockContext.java for more information.
     */
    public synchronized LockContext databaseContext() {
        return context("database");
    }
}