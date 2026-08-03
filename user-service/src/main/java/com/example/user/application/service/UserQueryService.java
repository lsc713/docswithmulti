package com.example.user.application.service;

import com.example.user.application.interfaces.UserRepository;
import com.example.user.application.usecase.UserQueryUseCase;
import com.example.user.common.exception.application.UserNotFoundException;
import com.example.user.domain.entity.User;
import com.example.user.presentation.dto.MeResponse;
import com.example.user.presentation.dto.UserListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

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

    @Override
    @Transactional(readOnly = true)
    public UserListResponse listUsers(int page, int size) {
        // ponytail: 전량 조회 후 메모리 페이지네이션 — 회원 수 소규모 전제.
        // 규모 커지면 UserRepository에 Pageable 조회 추가로 교체.
        List<User> all = userRepository.findAll().stream()
                .sorted(Comparator.comparing(User::getId))
                .toList();
        int from = Math.min(Math.max(0, page) * Math.max(0, size), all.size());
        int to = Math.min(from + Math.max(0, size), all.size());
        List<UserListResponse.UserSummary> content = all.subList(from, to).stream()
                .map(u -> new UserListResponse.UserSummary(
                        u.getId(), u.getEmail(), u.getName(),
                        u.getRole().name(), u.getStatus().name(), u.getCreatedAt().toString()))
                .toList();
        return new UserListResponse(content, page, size, all.size());
    }
}
