package com.sdip.domain.repo;

import com.sdip.domain.entity.VendorCredential;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorCredentialRepository extends JpaRepository<VendorCredential, String> {
}
