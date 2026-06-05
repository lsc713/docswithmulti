package com.example.user.infrastructure.config;

import com.example.user.application.interfaces.*;
import com.example.user.infrastructure.persistence.*;
import com.example.user.infrastructure.security.BcryptPasswordEncoderAdapter;
import com.example.user.infrastructure.security.JwtTokenProvider;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = "com.example.user.infrastructure.persistence")
public class PersistenceConfig {
    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
    @Bean
    public UserRepository userRepository(UserJpaRepository jpa) { return new UserRepositoryImpl(jpa); }
    @Bean
    public AddressRepository addressRepository(AddressJpaRepository jpa) { return new AddressRepositoryImpl(jpa); }
    @Bean
    public PaymentMethodRepository paymentMethodRepository(PaymentMethodJpaRepository jpa) { return new PaymentMethodRepositoryImpl(jpa); }
    @Bean
    public RefreshTokenRepository refreshTokenRepository(RefreshTokenJpaRepository jpa) { return new RefreshTokenRepositoryImpl(jpa); }
    @Bean
    public PasswordEncoder passwordEncoder() { return new BcryptPasswordEncoderAdapter(); }
    @Bean
    public JwtTokenProvider jwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiry}") long accessTokenExpiry,
            @Value("${jwt.refresh-token-expiry}") long refreshTokenExpiry) {
        return new JwtTokenProvider(secret, accessTokenExpiry, refreshTokenExpiry);
    }
}
