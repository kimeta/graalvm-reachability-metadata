/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
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
import javax.transaction.Transaction;

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
}
