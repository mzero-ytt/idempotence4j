package com.transferwise.idempotence4j.core;

import com.transferwise.idempotence4j.core.exception.ResultSerializationException;
import com.transferwise.idempotence4j.core.exception.ConflictingActionException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.function.Function;
import java.util.function.Supplier;

@Slf4j
public class IdempotenceService {
    private final PlatformTransactionManager platformTransactionManager;
    private final LockProvider lockProvider;
    private final ActionRepository actionRepository;
    private final ResultSerializer resultSerializer;

    public IdempotenceService(
        @NonNull PlatformTransactionManager platformTransactionManager,
        @NonNull LockProvider lockProvider,
        @NonNull ActionRepository actionRepository,
        @NonNull ResultSerializer resultSerializer) {
        this.platformTransactionManager = platformTransactionManager;
        this.lockProvider = lockProvider;
        this.actionRepository = actionRepository;
        this.resultSerializer = resultSerializer;
    }

    public <R> R execute(ActionId actionId, Supplier<R> action) {
        return this.execute(actionId, Function.identity(), action, Function.identity());
    }

    /**
     * Idempotent action execution orchestrator that provides a guarantee that no action
     * with exact same identifier going to be executed twice
     *
     * @param <R> the type of the action result
     * @param <S> the type of the persisted action result
     *
     * @param actionId identifier of the action
     * @param onRetry procedure executed when clients try to re-try previously completed request with committed result
     * @param procedure action execution body
     * @param toRecord mapping of procedure result to the model that going to be persisted and provided {@param onRetry}
     */
    public <S, R> R execute(ActionId actionId, Function<S, R> onRetry, Supplier<R> procedure, Function<R, S> toRecord) {
        Action action = newTransaction(TransactionDefinition.PROPAGATION_REQUIRES_NEW)
            .execute(status -> actionRepository.insertOrGet(new Action(actionId)));

        if (action.hasCompleted()) {
            return processRetry(action, onRetry);
        }

        return newTransaction(TransactionDefinition.PROPAGATION_REQUIRED).execute(status -> {
            Lock lock = lockProvider.lock(actionId).orElseThrow(() -> new ConflictingActionException("Request already in progress"));

            try (lock) {
                Action pendingAction = actionRepository.find(actionId).get();
                if (pendingAction.hasCompleted()) {
                    return processRetry(pendingAction, onRetry);
                }

                action.started();
                R result = procedure.get();
                S persistedResult = toRecord.apply(result);

                action.completed(serializeResult(persistedResult));
                actionRepository.update(action);

                return result;
            }
        });
    }

    private <S, R> R processRetry(Action action, Function<S, R> onRetry) {
        S result = readResult(action);
        return onRetry.apply(result);
    }

    private <S> Result serializeResult(S result) {
        try {
            byte[] byteContent = resultSerializer.serialize(result);
            return new Result(byteContent, resultSerializer.getType());
        } catch (IOException ex) {
            throw new ResultSerializationException("Failed to serialize persisted result", ex);
        }
    }

    private <S> S readResult(Action action) {
        try {
            S result = null;
            if (action.hasResult()) {
                result = resultSerializer.deserialize(action.getResult().map(Result::getContent).get());
            }

            return result;
        } catch (IOException ex) {
            throw new ResultSerializationException("Failed to de-serialize persisted result", ex);
        }
    }

    private TransactionTemplate newTransaction(int propagationLevel) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(platformTransactionManager);
        transactionTemplate.setPropagationBehavior(propagationLevel);
        return transactionTemplate;
    }
}