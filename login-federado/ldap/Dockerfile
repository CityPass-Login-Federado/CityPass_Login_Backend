FROM osixia/openldap:1.5.0

RUN cat >> /etc/ldap/schema/ppolicy.schema <<'EOF'
attributetype ( 1.3.6.1.4.1.42.2.27.8.1.16
	NAME 'pwdChangedTime'
	DESC 'The time the password was last changed'
	EQUALITY generalizedTimeMatch
	ORDERING generalizedTimeOrderingMatch
	SYNTAX 1.3.6.1.4.1.1466.115.121.1.24
	SINGLE-VALUE
	USAGE directoryOperation )
attributetype ( 1.3.6.1.4.1.42.2.27.8.1.17
	NAME 'pwdAccountLockedTime'
	DESC 'The time an user account was locked'
	EQUALITY generalizedTimeMatch
	ORDERING generalizedTimeOrderingMatch
	SYNTAX 1.3.6.1.4.1.1466.115.121.1.24
	SINGLE-VALUE
	USAGE directoryOperation )
attributetype ( 1.3.6.1.4.1.42.2.27.8.1.19
	NAME 'pwdFailureTime'
	DESC 'The timestamps of the last consecutive authentication failures'
	EQUALITY generalizedTimeMatch
	ORDERING generalizedTimeOrderingMatch
	SYNTAX 1.3.6.1.4.1.1466.115.121.1.24
	USAGE directoryOperation )
attributetype ( 1.3.6.1.4.1.42.2.27.8.1.20
	NAME 'pwdHistory'
	DESC 'The history of user passwords'
	EQUALITY octetStringMatch
	SYNTAX 1.3.6.1.4.1.1466.115.121.1.40
	USAGE directoryOperation )
attributetype ( 1.3.6.1.4.1.42.2.27.8.1.21
	NAME 'pwdGraceUseTime'
	DESC 'The timestamps of grace authentications after the password has expired'
	EQUALITY generalizedTimeMatch
	SYNTAX 1.3.6.1.4.1.1466.115.121.1.24
	USAGE directoryOperation )
attributetype ( 1.3.6.1.4.1.42.2.27.8.1.22
	NAME 'pwdReset'
	DESC 'The indication that the password has been reset'
	EQUALITY booleanMatch
	SYNTAX 1.3.6.1.4.1.1466.115.121.1.7
	SINGLE-VALUE
	USAGE directoryOperation )
attributetype ( 1.3.6.1.4.1.42.2.27.8.1.23
	NAME 'pwdPolicySubentry'
	DESC 'The pwdPolicy subentry in effect for this object'
	EQUALITY distinguishedNameMatch
	SYNTAX 1.3.6.1.4.1.1466.115.121.1.12
	SINGLE-VALUE
	USAGE directoryOperation )
EOF
