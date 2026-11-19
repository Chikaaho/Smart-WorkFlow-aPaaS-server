// package com.sw.ck.iot.dto;

// import com.sw.ck.iot.config.IotCipherProperties;
// import com.sw.ck.iot.entity.IotConnection;
// import com.sw.ck.iot.service.IotConnectionService;
// import org.junit.jupiter.api.Test;

// import java.time.LocalDateTime;
// import java.util.Base64;

// import static org.assertj.core.api.Assertions.assertThat;

// /** IoT 连接对外视图：字段映射、时间与凭证脱敏。 */
// class IotConnectionDTOTest {

//     @Test
//     void toVoShouldKeepFieldsAndMaskPassword() {
//         IotCipherProperties properties = new IotCipherProperties();
//         properties.setCipherKey(Base64.getEncoder().encodeToString(new byte[32]));
//         IotConnectionService service = new IotConnectionService(null, properties, null, null);

//         IotConnection conn = new IotConnection();
//         conn.setId(7L);
//         conn.setCode("mqtt-main");
//         conn.setName("主连接");
//         conn.setConnType("MQTT");
//         conn.setEnabled(1);
//         conn.setLastCheckTime(LocalDateTime.of(2026, 11, 16, 10, 0));
//         conn.setPasswordCipher("cipher");

//         IotConnectionVO dto = service.toVO(conn);

//         assertThat(dto.getId()).isEqualTo(7L);
//         assertThat(dto.getCode()).isEqualTo("mqtt-main");
//         assertThat(dto.getLastCheckTime()).isEqualTo(LocalDateTime.of(2026, 11, 16, 10, 0));
//         assertThat(dto.getPasswordMasked()).isEqualTo(IotConnectionService.PASSWORD_MASK);
//         assertThat(dto.getHasPassword()).isTrue();
//     }
// }
