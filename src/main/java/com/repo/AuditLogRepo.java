package com.repo;

import org.springframework.data.repository.CrudRepository;
import com.modal.AuditLog;

public interface AuditLogRepo extends CrudRepository<AuditLog, Long> {

}
