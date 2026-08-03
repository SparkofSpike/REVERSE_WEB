package com.test.engine.service;

import com.test.engine.dto.BuildRequest;
import com.test.engine.dto.BuildResponse;
import com.test.engine.entity.Build;
import com.test.engine.exception.BusinessException;
import com.test.engine.model.CardPack;
import com.test.engine.model.CardPackLoader;
import com.test.engine.model.CharacterTemplate;
import com.test.engine.repository.BuildRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * CRUD for user owned decks. Validates pack and character ids against the
 * loaded card packs.
 */
@Service
public class BuildService {

    private final BuildRepository buildRepository;
    private final CardPackLoader cardPackLoader;

    public BuildService(BuildRepository buildRepository, CardPackLoader cardPackLoader) {
        this.buildRepository = buildRepository;
        this.cardPackLoader = cardPackLoader;
    }

    @Transactional(readOnly = true)
    public List<BuildResponse> list(Long userId) {
        return buildRepository.findByUserIdOrderByUpdatedAtDesc(userId)
                .stream().map(BuildResponse::from).toList();
    }

    @Transactional
    public BuildResponse create(Long userId, BuildRequest request) {
        Build build = new Build();
        build.setUserId(userId);
        applyRequest(build, request);
        buildRepository.save(build);
        return BuildResponse.from(build);
    }

    @Transactional(readOnly = true)
    public BuildResponse get(Long userId, Long buildId) {
        return BuildResponse.from(owned(userId, buildId));
    }

    @Transactional
    public BuildResponse update(Long userId, Long buildId, BuildRequest request) {
        Build build = owned(userId, buildId);
        applyRequest(build, request);
        build.setUpdatedAt(Instant.now());
        buildRepository.save(build);
        return BuildResponse.from(build);
    }

    @Transactional
    public void delete(Long userId, Long buildId) {
        Build build = owned(userId, buildId);
        buildRepository.delete(build);
    }

    private void applyRequest(Build build, BuildRequest request) {
        CardPack pack = cardPackLoader.get(request.getPackId());
        List<String> ids = request.getCharacterIds();
        for (String characterId : ids) {
            boolean known = pack.getCharacters().stream()
                    .map(CharacterTemplate::getId)
                    .anyMatch(characterId::equals);
            if (!known) {
                throw new BusinessException("角色 " + characterId + " 不属于卡包 " + pack.getId());
            }
        }
        if (request.getInitialPerkId() != null && !request.getInitialPerkId().isBlank()) {
            boolean known = pack.getInitialPerks().stream()
                    .anyMatch(p -> p.getId().equals(request.getInitialPerkId()));
            if (!known) {
                throw new BusinessException("初始词条 " + request.getInitialPerkId() + " 不属于卡包 " + pack.getId());
            }
        }
        build.setName(request.getName().trim());
        build.setPackId(pack.getId());
        build.setCharacterIds(ids);
        build.setInitialPerkId(request.getInitialPerkId());
    }

    private Build owned(Long userId, Long buildId) {
        Build build = buildRepository.findById(buildId)
                .orElseThrow(() -> new BusinessException("构筑不存在"));
        if (!build.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该构筑");
        }
        return build;
    }
}
