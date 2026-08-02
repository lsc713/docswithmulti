package com.example.user.application.service;

import com.example.user.application.interfaces.UserRepository;
import com.example.user.application.usecase.AdminUseCase;
import com.example.user.common.exception.application.UserNotFoundException;
import com.example.user.domain.entity.User;
import com.example.user.domain.entity.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminService implements AdminUseCase {
    private final UserRepository userRepository;

    @Override
    @Transactional
    public RoleChangeResult changeRole(long userId, UserRole newRole) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        user.changeRole(newRole);
        User saved = userRepository.save(user);
        return new RoleChangeResult(saved.getId(), saved.getRole());
    }
}
