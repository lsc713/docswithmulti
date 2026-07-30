package com.example.user.infrastructure.persistence;

import com.example.user.domain.entity.User;
import com.example.user.domain.entity.UserRole;
import com.example.user.domain.entity.UserStatus;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "users")
public class UserJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private UserRole role;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private UserStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected UserJpaEntity() {}

    public static UserJpaEntity from(User user) {
        UserJpaEntity entity = new UserJpaEntity();
        entity.id = user.getId();
        entity.email = user.getEmail();
        entity.password = user.getPassword();
        entity.name = user.getName();
        entity.phone = user.getPhone();
        entity.role = user.getRole();
        entity.merchantId = user.getMerchantId();
        entity.status = user.getStatus();
        entity.createdAt = LocalDateTime.ofInstant(user.getCreatedAt(), ZoneOffset.UTC);
        entity.updatedAt = LocalDateTime.ofInstant(user.getUpdatedAt(), ZoneOffset.UTC);
        return entity;
    }

    public User toDomain() {
        return User.reconstruct(
                id, email, password, name, phone, role, merchantId, status,
                createdAt.toInstant(ZoneOffset.UTC),
                updatedAt.toInstant(ZoneOffset.UTC)
        );
    }

    public Long getId() { return id; }
}
