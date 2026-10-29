package com.example.order.core.repository;

import com.example.order.domain.AuthToken;
import com.example.order.domain.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class AuthTokenRepository {

    @Inject
    private EntityManager entityManager;

    public void persist(AuthToken token) {
        entityManager.persist(token);
    }

    public Optional<AuthToken> findByTokenHash(String tokenHash) {
        return entityManager.createQuery("""
                        select t from AuthToken t
                        join fetch t.user u
                        where t.tokenHash = :tokenHash
                          and u.enabled = true
                        """, AuthToken.class)
                .setParameter("tokenHash", tokenHash)
                .getResultStream()
                .findFirst();
    }

    public void delete(AuthToken token) {
        entityManager.remove(entityManager.contains(token) ? token : entityManager.merge(token));
    }

    /** Revokes every session of {@code user}. Call it when a user is disabled or changes password. */
    public int deleteByUser(User user) {
        return entityManager.createQuery("delete from AuthToken t where t.user = :user")
                .setParameter("user", user)
                .executeUpdate();
    }

    public int deleteExpired(LocalDateTime now) {
        return entityManager.createQuery("delete from AuthToken t where t.expiresAt < :now")
                .setParameter("now", now)
                .executeUpdate();
    }

    /**
     * Deletes everything but the {@code keep} most recently used live sessions of {@code user}.
     *
     * @return the number of deleted sessions
     */
    public int deleteOldestBeyond(User user, LocalDateTime now, int keep) {
        List<Long> ids = entityManager.createQuery("""
                        select t.id from AuthToken t
                        where t.user = :user
                          and t.expiresAt > :now
                        order by t.lastUsedAt desc, t.id desc
                        """, Long.class)
                .setParameter("user", user)
                .setParameter("now", now)
                .getResultList();
        if (ids.size() <= keep) {
            return 0;
        }
        List<Long> stale = ids.subList(keep, ids.size());
        return entityManager.createQuery("delete from AuthToken t where t.id in :ids")
                .setParameter("ids", stale)
                .executeUpdate();
    }
}
