#!/bin/bash
sed -i 's/gamification.member_points/gamification.points_earned/g' deploy/hub/src/test/java/io/loyaltyhub/hub/HubRedeliveryIT.java
