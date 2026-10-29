package com.example.order.core.repository;

import com.example.order.domain.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.util.Optional;

@ApplicationScoped
public class UserRepository {

    @Inject
    private EntityManager entityManager;

    public Optional<User> findByUsername(String username) {
        return entityManager.createQuery(
                        "select u from User u where u.username = :username", User.class)
                .setParameter("username", username)
                .getResultStream()
                .findFirst();
    }

    /**
     * Same as {@link #findByUsername} but takes a row lock until the transaction
     * ends, so concurrent logins for one user cannot lose failed-login updates.
     */
    public Optional<User> findByUsernameForUpdate(String username) {
        return entityManager.createQuery(
                        "select u from User u where u.username = :username", User.class)
                .setParameter("username", username)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream()
                .findFirst();
    }

    public long count() {
        return entityManager.createQuery("select count(u) from User u", Long.class)
                .getSingleResult();
    }

    public void persist(User user) {
        entityManager.persist(user);
    }
}
