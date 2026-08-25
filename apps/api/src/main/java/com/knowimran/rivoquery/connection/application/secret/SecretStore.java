package com.knowimran.rivoquery.connection.application.secret;

import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority.SecretAccessGrant;
import com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority.SecretMutationGrant;
import com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority.SecretRevocationGrant;

public interface SecretStore {
    StagedSecretReference stage(
            SecretBinding binding,
            DestructibleSecretMaterial material,
            SecretMutationGrant grant,
            Deadline deadline);

    SecretLease resolve(SecretReference reference, SecretAccessGrant grant, Deadline deadline);

    void revoke(SecretReference reference, SecretRevocationGrant grant, Deadline deadline);
}
