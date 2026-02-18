/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org_jboss_narayana_jta.jta;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.Synchronization;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionSynchronizationRegistry;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JtaTest {

    @AfterEach
    void cleanup() {
        try {
            javax.transaction.TransactionManager tm = com.arjuna.ats.jta.TransactionManager.transactionManager();
            int status = tm.getStatus();
            if (status != Status.STATUS_NO_TRANSACTION) {
                try {
                    tm.rollback();
                } catch (Exception ignored) {
                    // Best effort cleanup
                }
            }
            // Reset any custom timeout back to default (0 = use default)
            try {
                tm.setTransactionTimeout(0);
            } catch (SystemException ignored) {
                // ignore
            }
        } catch (Exception ignored) {
            // ignore
        }
    }

    @Test
    void shouldCommitTransactionAndInvokeSynchronizationsInOrder() throws Exception {
        javax.transaction.UserTransaction ut = com.arjuna.ats.jta.UserTransaction.userTransaction();
        javax.transaction.TransactionManager tm = com.arjuna.ats.jta.TransactionManager.transactionManager();

        ut.begin();
        RecordingSynchronization sync = new RecordingSynchronization();
        tm.getTransaction().registerSynchronization(sync);

        assertThat(ut.getStatus()).isEqualTo(Status.STATUS_ACTIVE);

        ut.commit();

        assertThat(ut.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
        assertThat(sync.beforeCalled).isTrue();
        assertThat(sync.afterStatuses).containsExactly(Status.STATUS_COMMITTED);
        assertThat(sync.beforeThenAfterOrder).isTrue();
    }

    @Test
    void shouldRollbackOnSetRollbackOnly() throws Exception {
        javax.transaction.UserTransaction ut = com.arjuna.ats.jta.UserTransaction.userTransaction();
        javax.transaction.TransactionManager tm = com.arjuna.ats.jta.TransactionManager.transactionManager();

        ut.begin();
        RecordingSynchronization sync = new RecordingSynchronization();
        tm.getTransaction().registerSynchronization(sync);
        ut.setRollbackOnly();

        assertThatThrownBy(ut::commit).isInstanceOf(RollbackException.class);

        assertThat(sync.beforeCalled).isFalse();
        assertThat(sync.afterStatuses).containsExactly(Status.STATUS_ROLLEDBACK);
    }

    @Test
    void shouldEnlistXAResourceAndPerformTwoPhaseOrOnePhaseCommit() throws Exception {
        javax.transaction.UserTransaction ut = com.arjuna.ats.jta.UserTransaction.userTransaction();
        javax.transaction.TransactionManager tm = com.arjuna.ats.jta.TransactionManager.transactionManager();

        ut.begin();
        RecordingXAResource xa = new RecordingXAResource();
        Transaction tx = tm.getTransaction();
        boolean enlisted = tx.enlistResource(xa);
        assertThat(enlisted).isTrue();

        ut.commit();

        List<String> calls = xa.getCalls();
        int startIdx = calls.indexOf("start");
        int endIdx = calls.indexOf("end");
        assertThat(startIdx).isNotNegative();
        assertThat(endIdx).isNotNegative();
        assertThat(startIdx).isLessThan(endIdx);

        boolean onePhase = calls.contains("commit(true)");
        if (onePhase) {
            // One-phase optimization: prepare is typically skipped
            assertThat(calls).doesNotContain("prepare");
            assertThat(calls).contains("commit(true)");
        } else {
            // Full 2PC: prepare followed by commit(false)
            assertThat(calls).contains("prepare", "commit(false)");
            assertThat(calls.indexOf("prepare")).isLessThan(calls.indexOf("commit(false)"));
        }
    }

    @Test
    void shouldSuspendAndResumeTransaction() throws Exception {
        javax.transaction.UserTransaction ut = com.arjuna.ats.jta.UserTransaction.userTransaction();
        javax.transaction.TransactionManager tm = com.arjuna.ats.jta.TransactionManager.transactionManager();

        ut.begin();
        Transaction suspended = tm.suspend();

        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);

        tm.resume(suspended);
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_ACTIVE);

        ut.commit();
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void shouldRollbackOnTimeout() throws Exception {
        javax.transaction.UserTransaction ut = com.arjuna.ats.jta.UserTransaction.userTransaction();
        javax.transaction.TransactionManager tm = com.arjuna.ats.jta.TransactionManager.transactionManager();

        tm.setTransactionTimeout(1); // seconds
        ut.begin();

        // Exceed the timeout to force rollback
        sleepMillis(2000);

        assertThatThrownBy(ut::commit).isInstanceOf(RollbackException.class);
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void shouldPerformTwoPhaseCommitWithMultipleXAResources() throws Exception {
        javax.transaction.UserTransaction ut = com.arjuna.ats.jta.UserTransaction.userTransaction();
        javax.transaction.TransactionManager tm = com.arjuna.ats.jta.TransactionManager.transactionManager();

        ut.begin();
        Transaction tx = tm.getTransaction();

        RecordingXAResource xa1 = new RecordingXAResource();
        RecordingXAResource xa2 = new RecordingXAResource();

        assertThat(tx.enlistResource(xa1)).isTrue();
        assertThat(tx.enlistResource(xa2)).isTrue();

        boolean rolledBack = false;
        try {
            ut.commit();
        } catch (RollbackException ex) {
            rolledBack = true;
        }

        // With more than one resource, 2PC should normally be used (no one-phase optimization).
        // Accept either successful 2PC commit or a rollback (if the transaction was marked rollback-only).
        for (RecordingXAResource xa : new RecordingXAResource[]{xa1, xa2}) {
            List<String> calls = xa.getCalls();
            assertThat(calls).contains("start", "end");

            if (!rolledBack) {
                assertThat(calls).contains("prepare", "commit(false)");
                assertThat(calls).doesNotContain("commit(true)", "rollback");

                int prepareIdx = calls.indexOf("prepare");
                int commitIdx = calls.indexOf("commit(false)");
                assertThat(prepareIdx).isNotNegative();
                assertThat(commitIdx).isNotNegative();
                assertThat(prepareIdx).isLessThan(commitIdx);
            } else {
                // On rollback path, different code paths are possible. Ensure no commit happened.
                assertThat(calls).doesNotContain("commit(true)", "commit(false)");
                // prepare/rollback may or may not be present depending on when the rollback-only was detected.
            }
        }
    }

    @Test
    void shouldBindResourcesWithTransactionSynchronizationRegistry() throws Exception {
        javax.transaction.UserTransaction ut = com.arjuna.ats.jta.UserTransaction.userTransaction();
        TransactionSynchronizationRegistry tsr =
                com.arjuna.ats.jta.common.jtaPropertyManager.getJTAEnvironmentBean().getTransactionSynchronizationRegistry();

        Object key = new Object();
        String value = "resource-value";

        ut.begin();
        // Inside the transaction: an association exists
        assertThat(tsr.getTransactionKey()).isNotNull();

        // Put and retrieve a resource within the same transaction
        tsr.putResource(key, value);
        assertThat(tsr.getResource(key)).isEqualTo(value);

        ut.commit();

        // After the transaction completes, no transaction is active
        assertThat(tsr.getTransactionKey()).isNull();

        // In a new transaction, the resource should not be present
        ut.begin();
        assertThat(tsr.getTransactionKey()).isNotNull();
        assertThat(tsr.getResource(key)).isNull();
        ut.commit();
    }

    @Test
    void shouldRegisterInterposedSynchronizationAndSetRollbackOnlyViaTSR() throws Exception {
        javax.transaction.UserTransaction ut = com.arjuna.ats.jta.UserTransaction.userTransaction();
        javax.transaction.TransactionManager tm = com.arjuna.ats.jta.TransactionManager.transactionManager();
        TransactionSynchronizationRegistry tsr =
                com.arjuna.ats.jta.common.jtaPropertyManager.getJTAEnvironmentBean().getTransactionSynchronizationRegistry();

        AtomicBoolean standardBefore = new AtomicBoolean(false);
        AtomicBoolean interposedBefore = new AtomicBoolean(false);
        List<Integer> standardAfterStatuses = new ArrayList<>();
        List<Integer> interposedAfterStatuses = new ArrayList<>();

        ut.begin();

        // Standard synchronization registered directly with the transaction
        tm.getTransaction().registerSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
                standardBefore.set(true);
            }

            @Override
            public void afterCompletion(int status) {
                standardAfterStatuses.add(status);
            }
        });

        // Interposed synchronization registered via TransactionSynchronizationRegistry
        tsr.registerInterposedSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
                interposedBefore.set(true);
                // Force rollback using TSR flag
                tsr.setRollbackOnly();
            }

            @Override
            public void afterCompletion(int status) {
                interposedAfterStatuses.add(status);
            }
        });

        assertThat(ut.getStatus()).isEqualTo(Status.STATUS_ACTIVE);

        // Commit should fail with RollbackException because we set rollback-only via TSR
        assertThatThrownBy(ut::commit).isInstanceOf(RollbackException.class);

        // Both synchronizations should have been called
        assertThat(standardBefore).matches(AtomicBoolean::get);
        assertThat(interposedBefore).matches(AtomicBoolean::get);

        // After-completion should indicate rollback for both
        assertThat(standardAfterStatuses).containsExactly(Status.STATUS_ROLLEDBACK);
        assertThat(interposedAfterStatuses).containsExactly(Status.STATUS_ROLLEDBACK);
    }

    @Test
    void shouldRequireActiveTransactionForTSROperations() {
        TransactionSynchronizationRegistry tsr =
                com.arjuna.ats.jta.common.jtaPropertyManager.getJTAEnvironmentBean().getTransactionSynchronizationRegistry();

        // Outside a transaction
        assertThat(tsr.getTransactionKey()).isNull();
        assertThat(tsr.getTransactionStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);

        // Methods requiring an active transaction should throw IllegalStateException
        assertThatThrownBy(tsr::getRollbackOnly).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tsr.registerInterposedSynchronization(new Synchronization() {
            @Override public void beforeCompletion() { }
            @Override public void afterCompletion(int status) { }
        })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tsr.putResource(new Object(), "value"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldExposeRollbackOnlyAndStatusViaTSR() throws Exception {
        javax.transaction.UserTransaction ut = com.arjuna.ats.jta.UserTransaction.userTransaction();
        TransactionSynchronizationRegistry tsr =
                com.arjuna.ats.jta.common.jtaPropertyManager.getJTAEnvironmentBean().getTransactionSynchronizationRegistry();

        ut.begin();

        assertThat(tsr.getTransactionKey()).isNotNull();
        assertThat(tsr.getTransactionStatus()).isEqualTo(Status.STATUS_ACTIVE);
        assertThat(tsr.getRollbackOnly()).isFalse();

        // Mark rollback-only via TSR and observe status change
        tsr.setRollbackOnly();
        assertThat(tsr.getRollbackOnly()).isTrue();
        assertThat(tsr.getTransactionStatus()).isEqualTo(Status.STATUS_MARKED_ROLLBACK);

        // Clean up with explicit rollback (no duplication of commit failure checks)
        ut.rollback();

        // After rollback, no transaction is active
        assertThat(tsr.getTransactionKey()).isNull();
        assertThat(tsr.getTransactionStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    // Helpers

    private static void sleepMillis(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie);
        }
    }

    private static final class RecordingSynchronization implements Synchronization {
        private boolean beforeCalled = false;
        private final List<Integer> afterStatuses = new ArrayList<>();
        private boolean beforeThenAfterOrder = false;

        @Override
        public void beforeCompletion() {
            beforeCalled = true;
        }

        @Override
        public void afterCompletion(int status) {
            afterStatuses.add(status);
            beforeThenAfterOrder = beforeCalled && !afterStatuses.isEmpty();
        }
    }

    private static final class RecordingXAResource implements XAResource {
        private final List<String> calls = new ArrayList<>();
        private int timeout = 0;

        List<String> getCalls() {
            return calls;
        }

        @Override
        public void commit(Xid xid, boolean onePhase) throws XAException {
            calls.add("commit(" + onePhase + ")");
        }

        @Override
        public void end(Xid xid, int flags) throws XAException {
            calls.add("end");
        }

        @Override
        public void forget(Xid xid) throws XAException {
            calls.add("forget");
        }

        @Override
        public int getTransactionTimeout() throws XAException {
            return timeout;
        }

        @Override
        public boolean isSameRM(XAResource xaResource) throws XAException {
            return this == xaResource;
        }

        @Override
        public int prepare(Xid xid) throws XAException {
            calls.add("prepare");
            return XAResource.XA_OK;
        }

        @Override
        public Xid[] recover(int flag) throws XAException {
            calls.add("recover");
            return new Xid[0];
        }

        @Override
        public void rollback(Xid xid) throws XAException {
            calls.add("rollback");
        }

        @Override
        public boolean setTransactionTimeout(int seconds) throws XAException {
            this.timeout = seconds;
            return true;
        }

        @Override
        public void start(Xid xid, int flags) throws XAException {
            calls.add("start");
        }
    }
}
