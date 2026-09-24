#!/bin/bash
sed -i 's/if (!firstTime) {/if (false) {/g' libs/lh-common/src/main/java/io/loyaltyhub/common/inbox/IdempotentHandler.java
