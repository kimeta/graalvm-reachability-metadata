/*
 * Copyright and related rights waived via CC0
 *
 * You should have received the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org_jboss_narayana_jta.jta;

import com.arjuna.ats.jta.TransactionManager;
import com.arjuna.ats.jta.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.Synchronization;
import javax.transaction.Transaction;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JtaTest {

    @AfterEach
    void cleanup() throws Exception {
        javax.transaction.TransactionManager tm = TransactionManager.transactionManager();
        int status = tm.getStatus();
        if (status != Status.STATUS_NO_TRANSACTION) {
            try {
                tm.rollback();
            } catch (Exception ignored) {
                // Ensure clean state even if rollback fails
            }
        }
    }

    @Test
    void beginAndRollback_withUserTransaction_changesStatusBackToNoTransaction() throws Exception {
        javax.transaction.UserTransaction ut = UserTransaction.userTransaction();
        javax.transaction.TransactionManager tm = TransactionManager.transactionManager();

        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);

        ut.begin();
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_ACTIVE);

        ut.rollback();
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void nestedBegin_isNotSupported() throws Exception {
        javax.transaction.UserTransaction ut = UserTransaction.userTransaction();
        javax.transaction.TransactionManager tm = TransactionManager.transactionManager();

        ut.begin();
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_ACTIVE);

        assertThatThrownBy(ut::begin).isInstanceOf(NotSupportedException.class);

        ut.rollback();
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void setRollbackOnly_causesRollbackExceptionOnCommit() throws Exception {
        javax.transaction.UserTransaction ut = UserTransaction.userTransaction();
        javax.transaction.TransactionManager tm = TransactionManager.transactionManager();

        ut.begin();
        Transaction tx = tm.getTransaction();
        assertThat(tx).isNotNull();

        tx.setRollbackOnly();

        assertThatThrownBy(ut::commit).isInstanceOf(RollbackException.class);
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void suspendAndResume_restoresSuspendedTransaction() throws Exception {
        javax.transaction.UserTransaction ut = UserTransaction.userTransaction();
        javax.transaction.TransactionManager tm = TransactionManager.transactionManager();

        ut.begin();
        Transaction tx = tm.getTransaction();
        assertThat(tx).isNotNull();

        Transaction suspended = tm.suspend();
        assertThat(suspended).isSameAs(tx);
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);

        tm.resume(suspended);
        assertThat(tm.getTransaction()).isSameAs(tx);

        ut.rollback();
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void registerSynchronization_onCommit_invokesCallbacksWithCommittedStatus() throws Exception {
        javax.transaction.UserTransaction ut = UserTransaction.userTransaction();
        javax.transaction.TransactionManager tm = TransactionManager.transactionManager();

        ut.begin();
        Transaction tx = tm.getTransaction();
        assertThat(tx).isNotNull();

        AtomicInteger beforeCalls = new AtomicInteger(0);
        AtomicReference<Integer> afterStatus = new AtomicReference<>();

        tx.registerSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
                beforeCalls.incrementAndGet();
            }

            @Override
            public void afterCompletion(int status) {
                afterStatus.set(status);
            }
        });

        ut.commit();

        assertThat(beforeCalls.get()).isEqualTo(1);
        assertThat(afterStatus.get()).isEqualTo(Status.STATUS_COMMITTED);
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void transactionEnlistXAResource_onePhaseCommit_invokesCommitWithoutPrepare() throws Exception {
        javax.transaction.UserTransaction ut = UserTransaction.userTransaction();
        javax.transaction.TransactionManager tm = TransactionManager.transactionManager();

        ut.begin();
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_ACTIVE);

        Transaction tx = tm.getTransaction();
        assertThat(tx).isNotNull();

        RecordingXAResource resource = new RecordingXAResource();
        boolean enlisted = tx.enlistResource(resource);
        assertThat(enlisted).isTrue();

        ut.commit();

        assertThat(resource.prepareCalled).isFalse();
        assertThat(resource.commitCalled).isTrue();
        assertThat(resource.onePhaseCommit).isTrue();
        assertThat(resource.rollbackCalled).isFalse();

        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    private static final class RecordingXAResource implements XAResource {
        volatile boolean startCalled = false;
        volatile boolean endCalled = false;
        volatile boolean prepareCalled = false;
        volatile boolean commitCalled = false;
        volatile boolean onePhaseCommit = false;
        volatile boolean rollbackCalled = false;
        volatile Xid seenXid = null;

        @Override
        public void commit(Xid xid, boolean onePhase) {
            this.commitCalled = true;
            this.onePhaseCommit = onePhase;
            this.seenXid = xid;
        }

        @Override
        public void end(Xid xid, int flags) {
            this.endCalled = true;
            this.seenXid = xid;
        }

        @Override
        public void forget(Xid xid) {
            // no-op
        }

        @Override
        public int getTransactionTimeout() {
            return 0;
        }

        @Override
        public boolean isSameRM(XAResource xaResource) {
            return false;
        }

        @Override
        public int prepare(Xid xid) {
            this.prepareCalled = true;
            this.seenXid = xid;
            return XA_OK;
        }

        @Override
        public Xid[] recover(int flag) {
            return new Xid[0];
        }

        @Override
        public void rollback(Xid xid) {
            this.rollbackCalled = true;
            this.seenXid = xid;
        }

        @Override
        public boolean setTransactionTimeout(int seconds) {
            return false;
        }

        @Override
        public void start(Xid xid, int flags) {
            this.startCalled = true;
            this.seenXid = xid;
        }
    }
}
