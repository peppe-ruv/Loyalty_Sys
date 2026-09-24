#!/bin/bash
sed -i 's/game_play/gamification.game_play/g' deploy/hub/src/test/java/io/loyaltyhub/hub/HubRedeliveryIT.java
sed -i 's/coupon WHERE owner_id/reward.coupon WHERE member_id/g' deploy/hub/src/test/java/io/loyaltyhub/hub/HubRedeliveryIT.java
sed -i 's/member_badge/gamification.member_badge/g' deploy/hub/src/test/java/io/loyaltyhub/hub/HubRedeliveryIT.java
sed -i 's/audit_entry/insight.audit_entry/g' deploy/hub/src/test/java/io/loyaltyhub/hub/HubRedeliveryIT.java
sed -i 's/member_stats/member.member_stats/g' deploy/hub/src/test/java/io/loyaltyhub/hub/HubRedeliveryIT.java
sed -i 's/member_activity_day/member.member_activity_day/g' deploy/hub/src/test/java/io/loyaltyhub/hub/HubRedeliveryIT.java
