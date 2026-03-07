package ru.batoyan.vkr.usecase;

/**
 * @author batoyan.rl
 * @since 23.02.2026
 */

import ru.notification.facade.proto.v1.CancelEventResponse;
import ru.notification.facade.proto.v1.CreateEventRequest;
import ru.notification.facade.proto.v1.*;

/**
 * @author batoyan.rl
 * @since 23.02.2026
 */

public interface NotificationFacadeUseCase {
    CreateEventResponse create(CreateEventRequest req, String clientId);
    UpdateEventResponse update(UpdateEventRequest req, String clientId);
    CancelEventResponse cancel(CancelEventRequest req, String clientId);

    GetEventResponse getEvent(GetEventRequest req, String clientId);
    ListEventsResponse listEvents(ListEventsRequest req, String clientId);

    SetAudienceResponse setAudience(SetAudienceRequest req, String clientId);
    AddRecipientsResponse addRecipients(AddRecipientsRequest req, String clientId);
    RemoveRecipientsResponse removeRecipients(RemoveRecipientsRequest req, String clientId);
    GetAudienceResponse getAudience(GetAudienceRequest req, String clientId);

    TriggerDispatchResponse triggerDispatch(TriggerDispatchRequest req, String clientId);
    GetDispatchResponse getDispatch(GetDispatchRequest req, String clientId);
    ListDispatchesResponse listDispatches(ListDispatchesRequest req, String clientId);
}