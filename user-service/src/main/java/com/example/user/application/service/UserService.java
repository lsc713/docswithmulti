package com.example.user.application.service;

import com.example.user.common.exception.application.UserNotFoundException;
import com.example.user.application.interfaces.PasswordEncoder;
import com.example.user.application.interfaces.UserRepository;
import com.example.user.application.usecase.UserUseCase;
import com.example.user.domain.entity.User;
import com.example.user.common.exception.domain.InvalidCredentialsException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService implements UserUseCase {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional(readOnly = true)
    public User getMe(long userId) {
        return userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
    }

    @Override
    @Transactional
    public User update(long userId, UpdateCommand command) {
        User user = getMe(userId);
        user.updateProfile(command.name(), command.phone());
        return userRepository.save(user);
    }

    @Override
    @Transactional
    public void changePassword(long userId, ChangePasswordCommand command) {
        User user = getMe(userId);
        if (!passwordEncoder.matches(command.currentPassword(), user.getPassword())) {
            throw new InvalidCredentialsException();
        }
        user.changePassword(passwordEncoder.encode(command.newPassword()));
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void withdraw(long userId) {
        User user = getMe(userId);
        user.withdraw();
        userRepository.save(user);
    }
}
