import re

with open("services/wallet-service/src/test/java/io/loyaltyhub/wallet/WalletPropTestIT.java", "r") as f:
    content = f.read()

# remove unused import
content = content.replace("import io.loyaltyhub.wallet.domain.ExpiryPolicy;\n", "")

with open("services/wallet-service/src/test/java/io/loyaltyhub/wallet/WalletPropTestIT.java", "w") as f:
    f.write(content)
