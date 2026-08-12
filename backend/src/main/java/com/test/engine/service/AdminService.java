package com.test.engine.service;

import com.test.engine.config.OpConfig;
import com.test.engine.dto.AdminUserView;
import com.test.engine.entity.User;
import com.test.engine.enums.UserRole;
import com.test.engine.exception.BusinessException;
import com.test.engine.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * OP-only account administration: user listing, role grants and
 * enable/disable. OP accounts themselves are configuration-driven and can
 * never be modified through this service.
 */
@Service
public class AdminService {

    private final UserRepository userRepository;
    private final OpConfig opConfig;

    public AdminService(UserRepository userRepository, OpConfig opConfig) {
        this.userRepository = userRepository;
        this.opConfig = opConfig;
    }

    @Transactional(readOnly = true)
    public List<AdminUserView> listUsers() {
        return userRepository.findAll().stream()
                .sorted(Comparator.comparing(User::getId))
                .map(this::toView)
                .toList();
    }

    @Transactional
    public AdminUserView setRole(Long operatorId, Long targetId, String role) {
        User target = requireModifiable(operatorId, targetId);
        target.setRole(UserRole.valueOf(role));
        return toView(target);
    }

    @Transactional
    public AdminUserView setEnabled(Long operatorId, Long targetId, boolean enabled) {
        User target = requireModifiable(operatorId, targetId);
        target.setEnabled(enabled);
        return toView(target);
    }

    private User requireModifiable(Long operatorId, Long targetId) {
        if (operatorId.equals(targetId)) {
            throw new BusinessException("不能操作自己的账号");
        }
        User target = userRepository.findById(targetId)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        if (opConfig.isOp(target.getId())) {
            throw new BusinessException("不能修改超级管理员");
        }
        return target;
    }

    private AdminUserView toView(User user) {
        return new AdminUserView(user.getId(), user.getUsername(), user.getNickname(),
                effectiveRole(user), user.isEnabled(), user.getCreatedAt());
    }

    private String effectiveRole(User user) {
        return opConfig.isOp(user.getId()) ? "OP"
                : (user.getRole() != null ? user.getRole().name() : UserRole.USER.name());
    }
}
