/**
 * Prints a scrypt hash for a client secret, to be pasted into
 * config/idp.json as `clients.<id>.secretHash`.
 *
 *     npm run hash-secret -- '<secret>'
 *     tsx scripts/hash-secret.ts '<secret>'
 *
 * The plaintext secret is never written anywhere by this script.
 */
import { hashSecret } from '../src/clients';

const secret = process.argv[2];

if (!secret) {
  process.stderr.write('usage: tsx scripts/hash-secret.ts <secret>\n');
  process.exit(1);
}

process.stdout.write(`${hashSecret(secret)}\n`);
