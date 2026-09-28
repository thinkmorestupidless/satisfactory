package satisfactory.api

import com.thinkmorestupidless.ankka.core.ErrorCode
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import satisfactory.api.application.*

import java.time.Instant

class TenantEntitySuite extends munit.FunSuite:

  private val t0 = Instant.parse("2026-09-28T10:00:00Z")

  private def kit(limits: Limits = Limits(2, 3, 3600, 86400)) =
    val k = EventSourcedTestKit.of(TenantEntity, "t_1")
    val _ = k.call(TenantEntity.create)(CreateTenant("acme", Some("alice"), limits, "op", t0))
    k

  test("the first admin is a member; the last admin cannot be removed or demoted") {
    val k = kit()
    assertEquals(k.currentState.members("alice").role, Roles.Admin)
    assertEquals(k.call(TenantEntity.removeMember)(RemoveMember("alice")).error.code, ErrorCode.Conflict)
    assertEquals(k.call(TenantEntity.changeMemberRole)(MemberChange("alice", Roles.Member, "alice", t0)).error.code, ErrorCode.Conflict)
    val _ = k.call(TenantEntity.addMember)(MemberChange("bob", Roles.Admin, "alice", t0))
    assert(!k.call(TenantEntity.removeMember)(RemoveMember("alice")).isError)
  }

  test("slots are exact: granted up to the limit, idempotent per dataset, refused while paused") {
    val k = kit()
    assert(k.call(TenantEntity.acquireSlot)(SlotRequest("ds_1")).replyValue)
    assert(k.call(TenantEntity.acquireSlot)(SlotRequest("ds_1")).replyValue)
    assert(k.call(TenantEntity.acquireSlot)(SlotRequest("ds_2")).replyValue)
    assert(!k.call(TenantEntity.acquireSlot)(SlotRequest("ds_3")).replyValue)
    val _ = k.call(TenantEntity.releaseSlot)(SlotRequest("ds_1"))
    val _ = k.call(TenantEntity.pauseQueue)(ByWhom("op", t0))
    assert(!k.call(TenantEntity.acquireSlot)(SlotRequest("ds_3")).replyValue)
    val _ = k.call(TenantEntity.resumeQueue)(ByWhom("op", t0))
    assert(k.call(TenantEntity.acquireSlot)(SlotRequest("ds_3")).replyValue)
  }

  test("the submit bucket empties at the rate and refills with time") {
    val k = kit()
    assert((1 to 3).forall(_ => k.call(TenantEntity.takeSubmitToken)(At(t0)).replyValue))
    assert(!k.call(TenantEntity.takeSubmitToken)(At(t0)).replyValue)
    assert(k.call(TenantEntity.takeSubmitToken)(At(t0.plusSeconds(20))).replyValue, "a third of a minute refills one")
  }

  test("profiles: unique names per model, fifty at most, standard reserved") {
    val k = kit()
    def profile(id: String, name: String) = Profile(id, "m/v1", name, "", "standard", None, Map.empty, None, t0)
    assert(!k.call(TenantEntity.saveProfile)(profile("cp_1", "fast")).isError)
    assertEquals(k.call(TenantEntity.saveProfile)(profile("cp_2", "fast")).error.code, ErrorCode.Conflict)
    assertEquals(k.call(TenantEntity.saveProfile)(profile("standard", "x")).error.code, ErrorCode.Conflict)
    (2 to 50).foreach(i => assert(!k.call(TenantEntity.saveProfile)(profile(s"cp_$i", s"p$i")).isError))
    assertEquals(k.call(TenantEntity.saveProfile)(profile("cp_51", "p51")).error.code, ErrorCode.Conflict)
  }
