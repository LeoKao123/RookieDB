
package edu.berkeley.cs186.database.concurrency;

import edu.berkeley.cs186.database.TransactionContext;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LockContext wraps around LockManager to provide the hierarchical structure
 * of multigranularity locking. Calls to acquire/release/etc. locks should
 * be mostly done through a LockContext, which provides access to locking
 * methods at a certain point in the hierarchy (database, table X, etc.)
 */
public class LockContext {
    // You should not remove any of these fields. You may add additional
    // fields/methods as you see fit.

    // The underlying lock manager.
    protected final LockManager lockman;

    // The parent LockContext object, or null if this LockContext is at the top of the hierarchy.
    protected final LockContext parent;

    // The name of the resource this LockContext represents.
    protected ResourceName name;

    // Whether this LockContext is readonly. If a LockContext is readonly, acquire/release/promote/escalate should
    // throw an UnsupportedOperationException.
    protected boolean readonly;

    // A mapping between transaction numbers, and the number of locks on children of this LockContext
    // that the transaction holds.
    protected final Map<Long, Integer> numChildLocks;

    // You should not modify or use this directly.
    protected final Map<String, LockContext> children;

    // Whether or not any new child LockContexts should be marked readonly.
    protected boolean childLocksDisabled;

    public LockContext(LockManager lockman, LockContext parent, String name) {
        this(lockman, parent, name, false);
    }

    protected LockContext(LockManager lockman, LockContext parent, String name,
                          boolean readonly) {
        this.lockman = lockman;
        this.parent = parent;
        if (parent == null) {
            this.name = new ResourceName(name);
        } else {
            this.name = new ResourceName(parent.getResourceName(), name);
        }
        this.readonly = readonly;
        this.numChildLocks = new ConcurrentHashMap<>();
        this.children = new ConcurrentHashMap<>();
        this.childLocksDisabled = readonly;
    }

    /**
     * Gets a lock context corresponding to `name` from a lock manager.
     */
    public static LockContext fromResourceName(LockManager lockman, ResourceName name) {
        Iterator<String> names = name.getNames().iterator();
        LockContext ctx;
        String n1 = names.next();
        ctx = lockman.context(n1);
        while (names.hasNext()) {
            String n = names.next();
            ctx = ctx.childContext(n);
        }
        return ctx;
    }

    /**
     * Get the name of the resource that this lock context pertains to.
     */
    public ResourceName getResourceName() {
        return name;
    }

    /**
     * Acquire a `lockType` lock, for transaction `transaction`.
     *
     * Note: you must make any necessary updates to numChildLocks, or else calls
     * to LockContext#getNumChildren will not work properly.
     *
     * @throws InvalidLockException if the request is invalid
     * @throws DuplicateLockRequestException if a lock is already held by the
     * transaction.
     * @throws UnsupportedOperationException if context is readonly
     */
    public void acquire(TransactionContext transaction, LockType lockType)
            throws InvalidLockException, DuplicateLockRequestException {
        // TODO(proj4_part2): implement
        if (this.readonly) {
            throw(new UnsupportedOperationException("unsupported operation"));
        }
        if (this.parent != null) {
            if (!LockType.canBeParentLock(this.parent.getLockType(transaction), lockType)) {
                throw (new InvalidLockException("invalid lock request"));
            }
        }
        lockman.acquire(transaction, this.name, lockType);
        if (this.parent != null) {
            this.parent.updateNumChildrenLock(transaction, 1);
        }
    }

    /**
     * Release `transaction`'s lock on `name`.
     *
     * Note: you *must* make any necessary updates to numChildLocks, or
     * else calls to LockContext#getNumChildren will not work properly.
     *
     * @throws NoLockHeldException if no lock on `name` is held by `transaction`
     * @throws InvalidLockException if the lock cannot be released because
     * doing so would violate multigranularity locking constraints
     * @throws UnsupportedOperationException if context is readonly
     */
    public void release(TransactionContext transaction)
            throws NoLockHeldException, InvalidLockException {
        // TODO(proj4_part2): implement
        if (this.readonly) {
            throw(new UnsupportedOperationException("unsupported operation"));
        }
        if (this.numChildLocks.get(transaction.getTransNum()) != null && this.numChildLocks.get(transaction.getTransNum()) != 0) {
            throw(new InvalidLockException("invalid release lock"));
        }
        lockman.release(transaction, this.name);
        if (this.parent != null) {
            this.parent.updateNumChildrenLock(transaction, 0);
        }
    }

    /**
     * Promote `transaction`'s lock to `newLockType`. For promotion to SIX from
     * IS/IX, all S and IS locks on descendants must be simultaneously
     * released. The helper function sisDescendants may be helpful here.
     *
     * Note: you *must* make any necessary updates to numChildLocks, or else
     * calls to LockContext#getNumChildren will not work properly.
     *
     * @throws DuplicateLockRequestException if `transaction` already has a
     * `newLockType` lock
     * @throws NoLockHeldException if `transaction` has no lock
     * @throws InvalidLockException if the requested lock type is not a
     * promotion or promoting would cause the lock manager to enter an invalid
     * state (e.g. IS(parent), X(child)). A promotion from lock type A to lock
     * type B is valid if B is substitutable for A and B is not equal to A, or
     * if B is SIX and A is IS/IX/S, and invalid otherwise. hasSIXAncestor may
     * be helpful here.
     * @throws UnsupportedOperationException if context is readonly
     */
    public void promote(TransactionContext transaction, LockType newLockType)
            throws DuplicateLockRequestException, NoLockHeldException, InvalidLockException {
        // TODO(proj4_part2): implement
        if (this.readonly) {
            throw(new UnsupportedOperationException("unsupported operation"));
        }
        if (this.parent != null) {
            if (!LockType.canBeParentLock(this.parent.getLockType(transaction), newLockType)) {
                throw (new InvalidLockException("invalid lock request"));
            }
        }
        if (newLockType == LockType.SIX) {
            if (this.hasSIXAncestor(transaction)) {
                throw (new InvalidLockException("invalid lock request"));
            }
            // release all descendants that are S or IS
            List<ResourceName> desSISResources = this.sisDescendants(transaction);
            // if (!desSISResources.isEmpty())
            desSISResources.add(this.name);
            this.lockman.acquireAndRelease(transaction, this.name, newLockType, desSISResources);
            this.updateNumChildrenLock(transaction, 0);
            return;
        }
        lockman.promote(transaction, this.name, newLockType);
    }

    /**
     * Escalate `transaction`'s lock from descendants of this context to this
     * level, using either an S or X lock. There should be no descendant locks
     * after this call, and every operation valid on descendants of this context
     * before this call must still be valid. You should only make *one* mutating
     * call to the lock manager, and should only request information about
     * TRANSACTION from the lock manager.
     *
     * For example, if a transaction has the following locks:
     *
     *                    IX(database)
     *                    /         \
     *               IX(table1)    S(table2)
     *                /      \
     *    S(table1 page3)  X(table1 page5)
     *
     * then after table1Context.escalate(transaction) is called, we should have:
     *
     *                    IX(database)
     *                    /         \
     *               X(table1)     S(table2)
     *
     * You should not make any mutating calls if the locks held by the
     * transaction do not change (such as when you call escalate multiple times
     * in a row).
     *
     * Note: you *must* make any necessary updates to numChildLocks of all
     * relevant contexts, or else calls to LockContext#getNumChildren will not
     * work properly.
     *
     * @throws NoLockHeldException if `transaction` has no lock at this level
     * @throws UnsupportedOperationException if context is readonly
     */
    public void escalate(TransactionContext transaction) throws NoLockHeldException {
        // TODO(proj4_part2): implement
        if (transaction == null) {
            return;
        }
        if (this.readonly) {
            throw(new UnsupportedOperationException("unsupported operation"));
        }
        LockType curLockType = this.getLockType(transaction);
        if (lockman.getLocks(transaction) == null || curLockType == LockType.NL) {
            throw(new NoLockHeldException("the transaction is not holding a lock"));
        }
        if (curLockType == LockType.S || curLockType == LockType.X) {
            return;
        }
        List<ResourceName> heldSISResources = this.sisDescendants(transaction);
        List<ResourceName> heldResources = this.getAllDescendants(transaction);
        if (heldResources.size() == 0 && heldSISResources.size() == 0) {
            if (curLockType == LockType.IS || curLockType == LockType.SIX) {
                heldSISResources.add(this.name);
                this.lockman.acquireAndRelease(transaction, this.name, LockType.S, heldSISResources);
            } else if (curLockType == LockType.IX) {
                heldResources.add(this.name);
                this.lockman.acquireAndRelease(transaction, this.name, LockType.X, heldResources);
            }
        } else if (heldResources.size() == heldSISResources.size()) {
            heldSISResources.add(this.name);
            this.lockman.acquireAndRelease(transaction, this.name, LockType.S, heldSISResources);
        } else {
            heldResources.add(this.name);
            this.lockman.acquireAndRelease(transaction, this.name, LockType.X, heldResources);
        }
        this.updateNumChildrenLock(transaction, 0);
    }

    /**
     * Get the type of lock that `transaction` holds at this level, or NL if no
     * lock is held at this level.
     */
    public LockType getExplicitLockType(TransactionContext transaction) {
        if (transaction == null) return LockType.NL;
        // TODO(proj4_part2): implement
        return lockman.getLockType(transaction, this.name);
    }

    /**
     * Gets the type of lock that the transaction has at this level, either
     * implicitly (e.g. explicit S lock at higher level implies S lock at this
     * level) or explicitly. Returns NL if there is no explicit nor implicit
     * lock.
     */
    public LockType getEffectiveLockType(TransactionContext transaction) {
        if (transaction == null) return LockType.NL;
        // TODO(proj4_part2): implement

        // first, get the lockcontext of current level
        LockType cur = getLockType(transaction);

        // then, loop through all ancestors until a non-NL lock is found or assign parentType to be NL
        LockContext parentContext = this.parent;
        LockType parentType = LockType.NL;
        while (parentContext != null && parentType == LockType.NL) {
            parentType = parentContext.getLockType(transaction);
            parentContext = parentContext.parentContext();
        }
        return getEffectiveLockTypeHelper(parentType, cur);
    }

    /**
     * [NEW] Helper function to get the effective lock type of child given type of parent and child
     * */
    private LockType getEffectiveLockTypeHelper(LockType parent, LockType cur) throws InvalidLockException{
        switch(parent) {
            case S:
                if (cur == LockType.NL) {
                    return LockType.S;
                }
                throw (new InvalidLockException("invalid lock type"));
            case X:
                if (cur == LockType.NL) {
                    return LockType.X;
                }
                throw (new InvalidLockException("invalid lock type"));
            case IS: case IX:
                if (LockType.canBeParentLock(parent, cur)) {
                    return cur;
                }
                throw (new InvalidLockException("invalid lock type"));
            case SIX:
                switch (cur){
                    case IX: return LockType.SIX;
                    case X: return LockType.X;
                    case NL: return LockType.S;
                    default:
                        throw (new InvalidLockException("invalid lock type"));
                }
        }
        return LockType.NL;
    }

    /**
     * [NEW] Helper function to get a lockContext's lockType
     * */
    private LockType getLockType(TransactionContext transaction) {
        return this.lockman.getLockType(transaction, this.name);
    }

    /**
     * Helper method to see if the transaction holds a SIX lock at an ancestor
     * of this context
     * @param transaction the transaction
     * @return true if holds a SIX at an ancestor, false if not
     */
    private boolean hasSIXAncestor(TransactionContext transaction) {
        // TODO(proj4_part2): implement
        LockContext ancestor = this;
        while (ancestor != null) {
            if (lockman.getLockType(transaction, ancestor.name) == LockType.SIX) {
                return true;
            }
            ancestor = ancestor.parent;
        }
        return false;
    }

    /**
     * Helper method to get a list of resourceNames of all locks that are S or
     * IS and are descendants of current context for the given transaction.
     * @param transaction the given transaction
     * @return a list of ResourceNames of descendants which the transaction
     * holds an S or IS lock.
     */
    private List<ResourceName> sisDescendants(TransactionContext transaction) {
        // TODO(proj4_part2): implement
        List<ResourceName> resourceList = new ArrayList<>();
        List<Lock> transactionLocks = lockman.getLocks(transaction);
        for (Lock l: transactionLocks) {
            if (l.lockType == LockType.IS || l.lockType == LockType.S) {
                if (this.isAncestorOf(fromResourceName(lockman, l.name))) {
                    resourceList.add(l.name);
                }
            }
        }
        return resourceList;
    }

    /**
     * [NEW] Helper function to get all descendant locks
     * */
    private List<ResourceName> getAllDescendants(TransactionContext transaction) {
        // TODO(proj4_part2): implement
        List<ResourceName> resourceList = new ArrayList<>();
        List<Lock> transactionLocks = lockman.getLocks(transaction);
        for (Lock l: transactionLocks) {
            if (this.isAncestorOf(fromResourceName(lockman, l.name))) {
                resourceList.add(l.name);
            }
        }
        return resourceList;
    }
    /**
     * Disables locking descendants. This causes all new child contexts of this
     * context to be readonly. This is used for indices and temporary tables
     * (where we disallow finer-grain locks), the former due to complexity
     * locking B+ trees, and the latter due to the fact that temporary tables
     * are only accessible to one transaction, so finer-grain locks make no
     * sense.
     */
    public void disableChildLocks() {
        this.childLocksDisabled = true;
    }

    /**
     * Gets the parent context.
     */
    public LockContext parentContext() {
        return parent;
    }

    /**
     * [NEW] check if this lockContext is an ancestor of lc
     *       in other words, lc is a descendant of this
     * */
    private boolean isAncestorOf(LockContext lc) {
        LockContext current = lc;
        while (current.parent != null) {
            if (current.parent.equals(this)) {
                return true;
            }
            current = current.parent;
        }
        return false;
    }

    /**
     * Gets the context for the child with name `name` and readable name
     * `readable`
     */
    public synchronized LockContext childContext(String name) {
        LockContext temp = new LockContext(lockman, this, name,
                this.childLocksDisabled || this.readonly);
        LockContext child = this.children.putIfAbsent(name, temp);
        if (child == null) child = temp;
        return child;
    }

    /**
     * Gets the context for the child with name `name`.
     */
    public synchronized LockContext childContext(long name) {
        return childContext(Long.toString(name));
    }

    /**
     * Gets the number of locks held on children a single transaction.
     */
    public int getNumChildren(TransactionContext transaction) {
        return numChildLocks.getOrDefault(transaction.getTransNum(), 0);
    }

    /**
     * [NEW] update the numChildLock
     * */
    private void updateNumChildrenLock(TransactionContext transaction, int update) {
        if (update == 0) { // then reset to 0
            this.numChildLocks.put(transaction.getTransNum(), 0);
            return;
        }
        int oldNum = getNumChildren(transaction);
        this.numChildLocks.put(transaction.getTransNum(), oldNum + update);
    }

    @Override
    public String toString() {
        return "LockContext(" + name.toString() + ")";
    }
}