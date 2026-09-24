#!/bin/bash
sed -i 's/gamification.points_earned/member.member_stats/g' deploy/hub/src/test/java/io/loyaltyhub/hub/HubRedeliveryIT.java
sed -i 's/sum(points)/sum(points_earned_total)/g' deploy/hub/src/test/java/io/loyaltyhub/hub/HubRedeliveryIT.java
