package com.sw.ck.bpm.process.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sw.ck.bpm.process.dto.CopyItemDTO;
import com.sw.ck.bpm.process.entity.CopyRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/** 抄送记录 Mapper。 */
@Mapper
public interface CopyRecordMapper extends BaseMapper<CopyRecord> {

    /**
     * 抄送我的分页查询（仅接收人本人；同接收人同事件去重）。
     * <p>
     * 手写 tenant_id + recipient_id 条件并跳过租户拦截器（对齐 NotifyMessageMapper 口径）。
     * 去重口径：同一接收人的同一抄送事件（process_instance_id + node_key + task_id）
     * 仅取最早一条；重试/补投产生的重复行不重复展示。
     * 稳定排序：create_time DESC, id DESC。
     * </p>
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("<script>"
            + "SELECT c.id, c.process_instance_id, c.node_key, c.task_id, c.recipient_id, "
            + "       c.delivery_status, c.create_time, "
            + "       i.form_key, i.process_def_key, i.business_key, i.initiator_id, i.status AS instance_status "
            + "FROM sw_bpm_copy_record c "
            + "LEFT JOIN sw_bpm_instance i ON i.process_instance_id = c.process_instance_id "
            + "         AND i.tenant_id = c.tenant_id AND i.deleted = 0 "
            + "WHERE c.deleted = 0 AND c.tenant_id = #{tenantId} AND c.recipient_id = #{recipientId} "
            + "  AND c.id IN (SELECT MIN(m.id) FROM sw_bpm_copy_record m "
            + "               WHERE m.deleted = 0 AND m.tenant_id = #{tenantId} "
            + "                 AND m.recipient_id = #{recipientId} "
            + "               GROUP BY m.process_instance_id, m.node_key, m.task_id, m.recipient_id) "
            + "<if test='processInstanceId != null and processInstanceId != \"\"'>"
            + "  AND c.process_instance_id = #{processInstanceId} "
            + "</if>"
            + "<if test='timeFrom != null'> AND c.create_time &gt;= #{timeFrom} </if>"
            + "<if test='timeTo != null'> AND c.create_time &lt;= #{timeTo} </if>"
            + "<if test='keyword != null and keyword != \"\"'>"
            + "  AND (i.form_key LIKE CONCAT('%', #{keyword}, '%') "
            + "       OR i.business_key LIKE CONCAT('%', #{keyword}, '%') "
            + "       OR i.process_def_key LIKE CONCAT('%', #{keyword}, '%') "
            + "       OR c.process_instance_id LIKE CONCAT('%', #{keyword}, '%'))"
            + " </if>"
            + "ORDER BY c.create_time DESC, c.id DESC "
            + "LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<CopyItemDTO> selectMyCopies(@Param("tenantId") Long tenantId,
                                     @Param("recipientId") String recipientId,
                                     @Param("processInstanceId") String processInstanceId,
                                     @Param("keyword") String keyword,
                                     @Param("timeFrom") LocalDateTime timeFrom,
                                     @Param("timeTo") LocalDateTime timeTo,
                                     @Param("limit") int limit,
                                     @Param("offset") int offset);

    /** 抄送我的去重后总数（与 {@link #selectMyCopies} 同过滤同去重口径）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("<script>"
            + "SELECT COUNT(*) FROM sw_bpm_copy_record c "
            + "LEFT JOIN sw_bpm_instance i ON i.process_instance_id = c.process_instance_id "
            + "         AND i.tenant_id = c.tenant_id AND i.deleted = 0 "
            + "WHERE c.deleted = 0 AND c.tenant_id = #{tenantId} AND c.recipient_id = #{recipientId} "
            + "  AND c.id IN (SELECT MIN(m.id) FROM sw_bpm_copy_record m "
            + "               WHERE m.deleted = 0 AND m.tenant_id = #{tenantId} "
            + "                 AND m.recipient_id = #{recipientId} "
            + "               GROUP BY m.process_instance_id, m.node_key, m.task_id, m.recipient_id) "
            + "<if test='processInstanceId != null and processInstanceId != \"\"'>"
            + "  AND c.process_instance_id = #{processInstanceId} "
            + "</if>"
            + "<if test='timeFrom != null'> AND c.create_time &gt;= #{timeFrom} </if>"
            + "<if test='timeTo != null'> AND c.create_time &lt;= #{timeTo} </if>"
            + "<if test='keyword != null and keyword != \"\"'>"
            + "  AND (i.form_key LIKE CONCAT('%', #{keyword}, '%') "
            + "       OR i.business_key LIKE CONCAT('%', #{keyword}, '%') "
            + "       OR i.process_def_key LIKE CONCAT('%', #{keyword}, '%') "
            + "       OR c.process_instance_id LIKE CONCAT('%', #{keyword}, '%'))"
            + " </if>"
            + "</script>")
    long countMyCopies(@Param("tenantId") Long tenantId,
                       @Param("recipientId") String recipientId,
                       @Param("processInstanceId") String processInstanceId,
                       @Param("keyword") String keyword,
                       @Param("timeFrom") LocalDateTime timeFrom,
                       @Param("timeTo") LocalDateTime timeTo);
}
