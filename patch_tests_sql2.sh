#!/bin/bash
sed -i 's/gamification.game_play/gamification.play/g' deploy/hub/src/test/java/io/loyaltyhub/hub/HubRedeliveryIT.java
sed -i 's/points_earned/points/g' deploy/hub/src/test/java/io/loyaltyhub/hub/HubRedeliveryIT.java
sed -i 's/member.member_stats/insight.metric_daily/g' deploy/hub/src/test/java/io/loyaltyhub/hub/HubRedeliveryIT.java
