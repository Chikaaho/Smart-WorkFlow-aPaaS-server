package com.sw.ck.bpm.engine.service.impl;

import org.springframework.beans.factory.annotation.Autowired;

import com.sw.ck.bpm.engine.entity.ExternalDatasource;
import com.sw.ck.bpm.engine.mapper.ExternalDatasourceMapper;
import com.sw.ck.bpm.engine.service.ExternalDatasourceService;
import com.sw.ck.common.crypto.AesGcmCipher;
import com.sw.ck.common.service.BaseServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

import lombok.extern.log4j.Log4j2;
/**
 * 外部数据源 Service 实现。
 * <p>
 * 密码加密/解密使用 {@link AesGcmCipher}，密钥从环境变量注入。
 * 查询返回时密码字段已被 {@code @JsonIgnore} 屏蔽，此处 decrypt 仅用于内部执行 SQL。
 * </p>
 */
@Service
@Log4j2
public class ExternalDatasourceServiceImpl
        extends BaseServiceImpl<ExternalDatasourceMapper, ExternalDatasource>
        implements ExternalDatasourceService {

    @Autowired
    private AesGcmCipher cipher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveWithEncryption(ExternalDatasource entity, String plaintextPassword) {
        Objects.requireNonNull(plaintextPassword, "Password must not be null");
        String encrypted = cipher.encrypt(plaintextPassword);
        entity.setPasswordCipher(encrypted);
        log.debug("ExternalDatasource save: name={}, password={}", entity.getName(), AesGcmCipher.mask(plaintextPassword));
        save(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateWithEncryption(ExternalDatasource entity, String plaintextPassword) {
        if (plaintextPassword != null && !plaintextPassword.isEmpty()) {
            String encrypted = cipher.encrypt(plaintextPassword);
            entity.setPasswordCipher(encrypted);
            log.debug("ExternalDatasource update: name={}, password updated {}", entity.getName(), AesGcmCipher.mask(plaintextPassword));
        } else {
            // 不修改密码：保留旧密文。从数据库加载旧值。
            ExternalDatasource existing = getById(entity.getId());
            if (existing != null) {
                entity.setPasswordCipher(existing.getPasswordCipher());
            }
            log.debug("ExternalDatasource update: name={}, password unchanged", entity.getName());
        }
        updateById(entity);
    }

    @Override
    public String decryptPassword(ExternalDatasource entity) {
        if (entity == null || entity.getPasswordCipher() == null) {
            throw new IllegalArgumentException("ExternalDatasource or password_cipher is null");
        }
        String plaintext = cipher.decrypt(entity.getPasswordCipher());
        log.debug("ExternalDatasource decrypt: name={}, password={}", entity.getName(), AesGcmCipher.mask(plaintext));
        return plaintext;
    }
}
