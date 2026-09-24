#!/bin/bash
sed -i 's/return firstTime;/return true;/g' libs/lh-common/src/main/java/io/loyaltyhub/common/inbox/IdempotentHandler.java
