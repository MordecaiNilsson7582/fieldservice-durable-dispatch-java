import java.util.List;
import java.util.Map;

public final class DispatchDecisionTest {
    public static void main(String[] args) {
        Map<String, Object> notice = FieldServiceServer.DispatchService.notice(Map.of(
                "order_id", "WO-41", "technician_id", "tech-7", "dispatch_status", "onsite",
                "photo_refs", List.of("photo-41"), "follow_up", "Confirm the replacement part"));
        if (!"onsite".equals(notice.get("dispatch_status")) || !List.of("photo-41").equals(notice.get("photo_refs")))
            throw new AssertionError("Dispatch evidence was lost");
        try {
            FieldServiceServer.DispatchService.notice(Map.of("order_id", "WO-41", "technician_id", "tech-7", "dispatch_status", "unknown"));
            throw new AssertionError("Invalid status was queued");
        } catch (IllegalArgumentException expected) { }
        System.out.println("Dispatch decision OK");
    }
}
