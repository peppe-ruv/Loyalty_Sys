#!/bin/bash
sed -i 's/if (false) {/if (!firstTime) {/g' libs/lh-common/src/main/java/io/loyaltyhub/common/inbox/IdempotentHandler.java
