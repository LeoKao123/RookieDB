
package edu.berkeley.cs186.database.concurrency;

import edu.berkeley.cs186.database.TransactionContext;

/**
 * LockUtil is a declarative layer which simplifies multigranularity lock
 * acquisition for the user (you, in the last task of Part 2). Generally
 * speaking, you should use LockUtil for lock acquisition instead of calling
 * LockContext methods directly.
 */
public class LockUtil {
    /**
     * Ensure that the current transaction can perform actions requiring
     * `requestType` on `lockContext`.
     *
     * `requestType` is guaranteed to be one of: S, X, NL.
     *
     * This method should promote/escalate/acquire as needed, but should only
     * grant the least permissive set of locks needed. We recommend that you
     * think about what to do in each of the following cases:
     * - The current lock type can effectively substitute the requested type
     * - The current lock type is IX and the requested lock is S
     * - The current lock type is an intent lock
     * - None of the above: In this case, consider what values the explicit
     *   lock type can be, and think about how ancestor looks will need to be
     *   acquired or changed.
     *
     * You may find it useful to create a helper method that ensures you have
     * the appropriate locks on all ancestors.
     */
    public static void ensureSufficientLockHeld(LockContext lockContext, LockType requestType) {
        // requestType must be S, X, or NL
        assert (requestType == LockType.S || requestType == LockType.X || requestType == LockType.NL);

        // Do nothing if the transaction or lockContext is null
        TransactionContext transaction = TransactionContext.getTransaction();
        if (transaction == null || lockContext == null) return;

        // You may find these variables useful
        LockContext parentContext = lockContext.parentContext();
        LockType effectiveLockType = lockContext.getEffectiveLockType(transaction);
        LockType explicitLockType = lockContext.getExplicitLockType(transaction);

        // TODO(proj4_part2): implement

        if (LockType.substitutable(effectiveLockType, requestType)) {
            // The current lock type can effectively substitute the requested type
            // do nothing
            return;
        } else if (effectiveLockType == LockType.IX && requestType == LockType.S) {
            // set current lock to SIX
            lockContext.promote(transaction, LockType.SIX);
        } else if (effectiveLockType.isIntent()) {
            // request the S or X lock on the intent lock
            // escalate all of the descendant
            lockContext.escalate(transaction); // end of it should be S or X
            explicitLockType = lockContext.getExplicitLockType(transaction);
            if (explicitLockType == LockType.S && requestType == LockType.S) {
                ensureSufficientLockHeld(lockContext, requestType);
            }
            // set the current lock
            // lockContext.promote(transaction, requestType);
            // update the ancestor

        } else {
            // explicit lock != NL
            //update(lockContext, requestType);
            if (effectiveLockType != LockType.NL) {
                // escalate all of the descendant
                lockContext.escalate(transaction);
            }

            // update the ancestor
            if (parentContext != null && !LockType.canBeParentLock(parentContext.getExplicitLockType(transaction), requestType)) {
                LockType expectedParentLockType = LockType.parentLock(requestType);
                LockUtil.update(parentContext, expectedParentLockType);
            }

            if (effectiveLockType != LockType.NL) {
                // set the current lock
                lockContext.promote(transaction, requestType);
            } else {
                // set the current lock
                lockContext.acquire(transaction, requestType);
            }

            //lockContext.promote(transaction, requestType);
        }
    }

    // TODO(proj4_part2) add any helper methods you want
    public static void update(LockContext lockContext, LockType requestLock) {
        TransactionContext transaction = TransactionContext.getTransaction();
        LockType curLockType = lockContext.getExplicitLockType(transaction);
        if (lockContext.parentContext() == null) {
            // update the current lock to requestLock
            if (curLockType == LockType.NL) {
                lockContext.acquire(transaction, requestLock);
            } else if (LockType.substitutable(requestLock, curLockType)) {
                lockContext.promote(transaction, requestLock);
            } else if ((requestLock == LockType.IX && curLockType == LockType.S) || (requestLock == LockType.S && curLockType == LockType.IX)) {
                lockContext.promote(transaction, LockType.SIX);
            } else {
                lockContext.escalate(transaction);
                lockContext.promote(transaction, requestLock);
            }
            return;
        }
        LockContext parentLockContext = lockContext.parentContext();
        LockType parentLockType = parentLockContext.getExplicitLockType(transaction);
        LockType expectedParentLockType = LockType.parentLock(requestLock);
        if (LockType.canBeParentLock(parentLockType, requestLock)) {
            // update the current lock to requestLock
            if (curLockType == LockType.NL) {
                lockContext.acquire(transaction, requestLock);
            } else if (LockType.substitutable(requestLock, curLockType)) {
                lockContext.promote(transaction, requestLock);
            } else if ((requestLock == LockType.IX && curLockType == LockType.S) || (requestLock == LockType.S && curLockType == LockType.IX)) {
                lockContext.promote(transaction, LockType.SIX);
            } else {
                lockContext.escalate(transaction);
                lockContext.promote(transaction, requestLock);
            }
            return;
        }
        // update the ancestor recursively
        update(parentLockContext, expectedParentLockType);
        // update the current lock to requestLock
        if (curLockType == LockType.NL) {
            lockContext.acquire(transaction, requestLock);
        } else if (LockType.substitutable(requestLock, curLockType)) {
            lockContext.promote(transaction, requestLock);
        } else if ((requestLock == LockType.IX && curLockType == LockType.S) || (requestLock == LockType.S && curLockType == LockType.IX)) {
            lockContext.promote(transaction, LockType.SIX);
        } else {
            lockContext.escalate(transaction);
            lockContext.promote(transaction, requestLock);
        }
    }
}
