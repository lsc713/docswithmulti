package com.example.user.application.service;

import com.example.user.application.interfaces.UserRepository;
import com.example.user.application.usecase.UserQueryUseCase;
import com.example.user.common.exception.application.UserNotFoundException;
import com.example.user.domain.entity.User;
import com.example.user.presentation.dto.MeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserQueryService implements UserQueryUseCase {
    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public MeResponse getProfile(long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        return new MeResponse(user.getId(), user.getEmail(), user.getName(), user.getRole().name());
    }
}
