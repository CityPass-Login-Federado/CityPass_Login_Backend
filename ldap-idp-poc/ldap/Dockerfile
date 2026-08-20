FROM debian:trixie-slim

# Non-interactive is required: the slapd postinst runs debconf and will
# otherwise block the build waiting for input.
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
 && apt-get install -y --no-install-recommends slapd ldap-utils \
 && rm -rf /var/lib/apt/lists/*

# R18 -- the slapd postinst leaves behind BOTH a default cn=config (with a
# hostname-derived suffix) AND a populated /var/lib/ldap/data.mdb. Both must go:
#
#   * /etc/ldap/slapd.d, or our config below layers on top of Debian's.
#     This removal MUST happen BEFORE the slapadd -n0 further down.
#   * /var/lib/ldap, because Docker seeds a fresh named volume from whatever
#     the image contains at that path. A leftover default data.mdb is copied
#     into the volume, and slapd then serves Debian's default suffix instead
#     of ours: no error, a working server, the wrong tree.
#
# Both are recreated empty and owned by openldap. If the path does not exist
# at mount time Docker creates the volume root-owned and slapd cannot write.
RUN rm -rf /etc/ldap/slapd.d /var/lib/ldap \
 && mkdir -p /etc/ldap/slapd.d /var/lib/ldap /var/run/slapd \
 && chown openldap:openldap /etc/ldap/slapd.d /var/lib/ldap /var/run/slapd \
 && chmod 750 /etc/ldap/slapd.d /var/lib/ldap

COPY config/ /ldif/config/
COPY seed/ /ldif/seed/
COPY seed-entrypoint.sh /usr/local/bin/seed-entrypoint.sh
RUN chmod +x /usr/local/bin/seed-entrypoint.sh

# Build cn=config OFFLINE, at image build time. This is what guarantees the
# "schema and overlays before seed" ordering structurally: the overlays are
# baked into the image, so they exist before the container has ever started
# and the seed cannot possibly land first.
#
# The stock schema LDIFs are spliced between the global part and the database
# part: olcSuffix: dc=citypass,dc=local cannot be parsed until dcObject is
# known, and employeeNumber needs inetOrgPerson.
#
# The `echo` inside the loop is not decoration: LDIF requires a blank line
# between entries and none of these files end with one. A plain `cat` of them
# fuses the last entry of one file onto the first of the next and slapadd
# fails with "entry has multiple DNs".
RUN for f in /ldif/config/00-base.ldif \
             /etc/ldap/schema/core.ldif \
             /etc/ldap/schema/cosine.ldif \
             /etc/ldap/schema/inetorgperson.ldif \
             /ldif/config/01-database.ldif \
             /ldif/config/02-overlays.ldif; do cat "$f"; echo; done > /tmp/slapd-config.ldif \
 && slapadd -n0 -F /etc/ldap/slapd.d -l /tmp/slapd-config.ldif \
 && rm /tmp/slapd-config.ldif \
 && chown -R openldap:openldap /etc/ldap/slapd.d

EXPOSE 389

# ponytail: -d 256 (stats) keeps slapd in the foreground and gives readable
# logs. Drop to -d 0 if the noise bothers you.
CMD ["slapd", "-h", "ldap://0.0.0.0:389/", "-F", "/etc/ldap/slapd.d", "-u", "openldap", "-g", "openldap", "-d", "256"]
