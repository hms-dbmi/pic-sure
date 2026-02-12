#!/bin/sh

# Directory where JAR files are located
JAR_DIR="/opt/jboss/wildfly/modules/system/layers/base/com/sql/mysql/main"

# Start of the module.xml file
cat <<EOF > ${JAR_DIR}/module.xml
<module xmlns="urn:jboss:module:1.1" name="com.sql.mysql">
    <resources>
EOF

# Loop through all JAR files and add them as resource-root entries
for jar in ${JAR_DIR}/*.jar; do
    echo "        <resource-root path=\"$(basename $jar)\"/>" >> ${JAR_DIR}/module.xml
done

# End of the module.xml file
cat <<EOF >> ${JAR_DIR}/module.xml
    </resources>
    <dependencies>
        <module name="javax.api"/>
    </dependencies>
</module>
EOF