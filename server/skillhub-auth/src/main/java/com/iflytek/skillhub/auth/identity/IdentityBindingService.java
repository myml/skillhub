package com.iflytek.skillhub.auth.identity;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.iflytek.skillhub.auth.entity.IdentityBinding;
import com.iflytek.skillhub.auth.oauth.OAuthClaims;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.auth.rbac.PlatformRoleDefaults;
import com.iflytek.skillhub.auth.repository.IdentityBindingRepository;
import com.iflytek.skillhub.auth.repository.UserRoleBindingRepository;
import com.iflytek.skillhub.domain.namespace.GlobalNamespaceMembershipService;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceMember;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.namespace.NamespaceType;
import com.iflytek.skillhub.domain.user.UserAccount;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
import com.iflytek.skillhub.domain.user.UserStatus;

/**
 * Resolves external OAuth identities to platform users, creating or updating
 * bindings and user records as needed.
 */
@Service
public class IdentityBindingService {

    private static final Logger log = LoggerFactory.getLogger(IdentityBindingService.class);

    private final IdentityBindingRepository bindingRepo;
    private final UserAccountRepository userRepo;
    private final UserRoleBindingRepository roleBindingRepo;
    private final GlobalNamespaceMembershipService globalNamespaceMembershipService;
    private final NamespaceRepository namespaceRepository;
    private final NamespaceMemberRepository namespaceMemberRepository;

    public IdentityBindingService(IdentityBindingRepository bindingRepo,
                                  UserAccountRepository userRepo,
                                  UserRoleBindingRepository roleBindingRepo,
                                  GlobalNamespaceMembershipService globalNamespaceMembershipService,
                                  NamespaceRepository namespaceRepository,
                                  NamespaceMemberRepository namespaceMemberRepository) {
        this.bindingRepo = bindingRepo;
        this.userRepo = userRepo;
        this.roleBindingRepo = roleBindingRepo;
        this.globalNamespaceMembershipService = globalNamespaceMembershipService;
        this.namespaceRepository = namespaceRepository;
        this.namespaceMemberRepository = namespaceMemberRepository;
    }

    @Transactional
    public PlatformPrincipal bindOrCreate(OAuthClaims claims, UserStatus initialStatus) {
        IdentityBinding binding = bindingRepo
            .findByProviderCodeAndSubject(claims.provider(), claims.subject())
            .orElse(null);

        UserAccount user;
        if (binding != null) {
            user = userRepo.findById(binding.getUserId())
                .orElseThrow(() -> new IllegalStateException("User not found for binding"));
            user.setDisplayName(claims.providerLogin());
            if (claims.email() != null) user.setEmail(claims.email());
            if (claims.extra().get("avatar_url") != null) {
                user.setAvatarUrl((String) claims.extra().get("avatar_url"));
            }
            user = userRepo.save(user);
        } else {
            user = new UserAccount(
                "usr_" + UUID.randomUUID(),
                claims.providerLogin(),
                claims.email(),
                (String) claims.extra().get("avatar_url")
            );
            user.setStatus(initialStatus);
            user = userRepo.save(user);
            if (initialStatus == UserStatus.ACTIVE) {
                globalNamespaceMembershipService.ensureMember(user.getId());
            }

            binding = new IdentityBinding(user.getId(), claims.provider(), claims.subject(), claims.providerLogin());
            bindingRepo.save(binding);
        }

        if (user.getStatus() == UserStatus.PENDING) {
            throw new com.iflytek.skillhub.auth.oauth.AccountPendingException();
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            throw new com.iflytek.skillhub.auth.oauth.AccountDisabledException();
        }

        Set<String> roles = roleBindingRepo.findByUserId(user.getId()).stream()
            .map(rb -> rb.getRole().getCode())
            .collect(Collectors.toSet());
        roles = PlatformRoleDefaults.withDefaultUserRole(roles);

        // Ensure personal namespace exists for the user
        ensurePersonalNamespace(user.getId(), claims.providerLogin());

        return new PlatformPrincipal(
            user.getId(), user.getDisplayName(), user.getEmail(),
            user.getAvatarUrl(), claims.provider(), roles
        );
    }

    /**
     * Ensures a personal namespace exists for the user with slug "user-{username}".
     * If the namespace already exists, ensures the user is a member with OWNER role.
     */
    private void ensurePersonalNamespace(String userId, String username) {
        String personalSlug = generatePersonalNamespaceSlug(username);
        
        Namespace existing = namespaceRepository.findBySlug(personalSlug).orElse(null);
        if (existing != null) {
            // Namespace already exists, ensure user is a member
            boolean isMember = namespaceMemberRepository
                .findByNamespaceIdAndUserId(existing.getId(), userId)
                .isPresent();
            if (!isMember) {
                log.info("Adding user {} as owner to existing personal namespace {}", userId, personalSlug);
                namespaceMemberRepository.save(
                    new NamespaceMember(existing.getId(), userId, NamespaceRole.OWNER)
                );
            }
            return;
        }
        
        // Create new personal namespace
        log.info("Creating personal namespace {} for user {}", personalSlug, userId);
        Namespace namespace = new Namespace(personalSlug, username + "'s Namespace", userId);
        namespace.setDescription("Personal namespace for " + username);
        namespace.setType(NamespaceType.TEAM);
        namespace = namespaceRepository.save(namespace);
        
        NamespaceMember ownerMember = new NamespaceMember(namespace.getId(), userId, NamespaceRole.OWNER);
        namespaceMemberRepository.save(ownerMember);
    }

    /**
     * Generates a personal namespace slug from username.
     * Format: user-{normalized-username}
     */
    private String generatePersonalNamespaceSlug(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username cannot be null or blank");
        }
        // Normalize: lowercase, replace non-alphanumeric with hyphens, remove consecutive hyphens
        String normalized = username.trim().toLowerCase()
            .replaceAll("[^\\p{L}\\p{N}\\p{So}]+", "-")
            .replaceAll("^-+", "")
            .replaceAll("-+$", "")
            .replaceAll("-{2,}", "-");
        
        // Ensure minimum length
        if (normalized.length() < 2) {
            normalized = "user-" + normalized;
        }
        
        return "user-" + normalized;
    }

    @Transactional
    public void createPendingUserIfAbsent(OAuthClaims claims) {
        IdentityBinding existingBinding = bindingRepo
            .findByProviderCodeAndSubject(claims.provider(), claims.subject())
            .orElse(null);
        if (existingBinding != null) {
            UserAccount existingUser = userRepo.findById(existingBinding.getUserId())
                .orElseThrow(() -> new IllegalStateException("User not found for binding"));
            if (existingUser.getStatus() == UserStatus.DISABLED) {
                throw new com.iflytek.skillhub.auth.oauth.AccountDisabledException();
            }
            throw new com.iflytek.skillhub.auth.oauth.AccountPendingException();
        }

        UserAccount user = new UserAccount(
            "usr_" + UUID.randomUUID(),
            claims.providerLogin(),
            claims.email(),
            (String) claims.extra().get("avatar_url")
        );
        user.setStatus(UserStatus.PENDING);
        user = userRepo.save(user);

        IdentityBinding binding = new IdentityBinding(user.getId(), claims.provider(), claims.subject(), claims.providerLogin());
        bindingRepo.save(binding);
    }
}
