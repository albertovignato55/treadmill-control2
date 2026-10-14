#!/usr/bin/env sh

##############################################################################
##
##  Gradle start up script for UN*X
##
##############################################################################

# Attempt to set APP_HOME
# Resolve links: \$0 may be a link
PRG="\$0"
# Need this for relative symlinks.
while [ -h "\$PRG" ] ; do
    ls=`ls -ld "$PRG"`
    link=`expr "$ls" : '.*-> \(.*\)$'`
    if expr "\$link" : '/.*' > /dev/null; then
        PRG="\$link"
    else
        PRG=`dirname "$PRG"`"/\$link"
    fi
done
SAVED="`pwd`"
cd `dirname "$PRG"`>/dev/null
APP_HOME="`pwd`"
cd "\$SAVED"

APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`

# Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM flags to this script.
DEFAULT_JVM_OPTS=""

# Use the maximum available, or set MAX_FD != -1 to use that value.
MAX_FD="maximum"

warn () {
    echo "\$*"
}

die () {
    echo
    echo "\$*"
    echo
    exit 1
}

# OS specific support (must be 'true' or 'false').
cygwin=false
msys=false
darwin=false
nonstop=false
case "`uname`" in
  CYGWIN* )
    cygwin=true
    ;;
  Darwin* )
    darwin=true
    ;;
  MSYS* )
    msys=true
    ;;
  NONSTOP* )
    nonstop=true
    ;;
esac

CLK_TCK=100
if [ -f /usr/include/sys/param.h ]; then
    CLK_TCK=`grep -E '^#define[[:space:]]+HZ[[:space:]]' /usr/include/sys/param.h | awk '{print $3}'`
    if [ -z "\$CLK_TCK" ]; then
        CLK_TCK=100
    fi
fi

# For Cygwin, ensure paths are in UNIX format before anything is touched.
if \$cygwin ; then
    [ -n "\$JAVA_HOME" ] && JAVA_HOME=`cygpath --unix "$JAVA_HOME"`
fi

# Determine the Java command to use to start the JVM.
if [ -n "\$JAVA_HOME" ] ; then
    if [ -x "\$JAVA_HOME/bin/java" ] ; then
        # Actual standard location
        JAVACMD="\$JAVA_HOME/bin/java"
    else
        die "ERROR: JAVA_HOME is set to an invalid directory: \$JAVA_HOME

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
fi

# Increase the maximum file descriptors if we can.
if [ "\(MAX_FD" = "maximum" ] \vert{}\vert{} [ "\)MAX_FD" = "max" ] ; then
    MAX_FD_LIMIT=`ulimit -H -n`
    if [ \$? -eq 0 ] ; then
        ulimit -n \$MAX_FD_LIMIT
    fi
fi

# For Darwin, add options to specfiy how the application shows up in the dock
if \$darwin; then
    GRADLE_OPTS="\$GRADLE_OPTS \"-Xdock:name=\(APP_NAME\" \"-Xdock:icon=\)APP_HOME/media/gradle.icns\""
fi

# For Cygwin, switch paths to Windows format before running java
if \$cygwin ; then
    APP_HOME=`cygpath --path --windows "$APP_HOME"`
    CLASSPATH=`cygpath --path --windows "$CLASSPATH"`
    JAVACMD=`cygpath --path --windows "$JAVACMD"`
fi

# Collect all arguments for the java command;
# double-quotes must be bypassed for words with spaces
# (each word becomes an argument to the java command)
eval set -- "\$DEFAULT_JVM_OPTS" "\(GRADLE_OPTS" "\"-Dorg.gradle.appname=\)APP_BASE_NAME\"" -classpath "\"\(APP_HOME/gradle/wrapper/gradle-wrapper.jar\"" org.gradle.wrapper.GradleWrapperMain '"\)@"'

exec "JAVACMD" "@"
