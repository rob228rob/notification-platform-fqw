package ru.batoyan.vkr.notification.profile.repository;

import ru.batoyan.vkr.notification.profile.model.RecipientProfileDomain;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface RecipientProfileRepository {

    Optional<RecipientProfileDomain> findByRecipientId(String recipientId);

    Map<String, RecipientProfileDomain> findAllByRecipientIds(Collection<String> recipientIds);
}
