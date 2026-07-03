package com.repo;

import org.springframework.data.repository.CrudRepository;

import com.modal.Email;

public interface EmailRepo extends CrudRepository<Email, Long> {

}
