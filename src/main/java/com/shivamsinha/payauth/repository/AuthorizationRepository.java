package com.shivamsinha.payauth.repository;

import com.shivamsinha.payauth.domain.Authorization;
import com.shivamsinha.payauth.domain.AuthorizationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AuthorizationRepository extends JpaRepository<Authorization, UUID> {

    List<Authorization> findTop20ByCardTokenOrderByCreatedAtDesc(String cardToken);

    long countByStatus(AuthorizationStatus status);
}
