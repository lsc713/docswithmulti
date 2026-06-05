package com.example.user.application.service;

import com.example.user.application.exception.UserNotFoundException;
import com.example.user.application.interfaces.UserRepository;
import com.example.user.application.usecase.AdminUseCase;
import com.example.user.domain.entity.User;
import com.example.user.domain.entity.UserRole;
import com.example.user.domain.entity.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminService implements AdminUseCase {
    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public List<User> getUsers() { return userRepository.findAll(); }

    @Override
    @Transactional
    public User updateStatus(long userId, UserStatus status) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        if (status == UserStatus.SUSPENDED) user.suspend();
        else if (status == UserStatus.ACTIVE) user.activate();
        return userRepository.save(user);
    }

    @Override
    @Transactional
    public User updateRole(long userId, UserRole role) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        user.changeRole(role);
        return userRepository.save(user);
    }
}
