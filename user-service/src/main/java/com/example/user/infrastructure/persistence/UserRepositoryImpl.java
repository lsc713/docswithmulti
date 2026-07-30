package com.example.user.infrastructure.persistence;

import com.example.user.application.interfaces.UserRepository;
import com.example.user.domain.entity.User;

import java.util.List;
import java.util.Optional;

public class UserRepositoryImpl implements UserRepository {

    private final UserJpaRepository jpa;

    public UserRepositoryImpl(UserJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<User> findById(long id) {
        return jpa.findById(id).map(UserJpaEntity::toDomain);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return jpa.findByEmail(email).map(UserJpaEntity::toDomain);
    }

    @Override
    public boolean existsByEmail(String email) {
        return jpa.existsByEmail(email);
    }

    @Override
    public User save(User user) {
        UserJpaEntity entity = UserJpaEntity.from(user);
        return jpa.save(entity).toDomain();
    }

    @Override
    public List<User> findAll() {
        return jpa.findAll().stream().map(UserJpaEntity::toDomain).toList();
    }
}
