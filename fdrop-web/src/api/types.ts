export type UserKind = "user" | "application";

export interface Me {
  sub: string;
  username: string;
  kind: UserKind;
  email?: string;
  roles: { standard: boolean; admin: boolean };
  quotaBytes: number;
  usedBytes: number;
  reservedBytes: number;
  csrfToken?: string;
  twoFactor: TwoFactorStatus;
  expiry: ExpirySettings;
  settings: {
    simpleUploadMax: number;
    maxFileSize: number;
    maxParallelChunks: number;
    clientHashMax: number;
    userManagement: boolean;
    forwarding: boolean;
    sftpPort?: number;
  };
}

/** File expiry settings for the signed-in user; expiries are canonical text such as "30 days". */
export interface ExpirySettings {
  enabled: boolean;
  /** The user's own default for files in Home. */
  homeDefault?: string;
  /** The server's default for Home when the user has none. */
  serverHomeDefault?: string;
  /** The longest expiry allowed in Home; when set, every Home file expires. */
  homeMax?: string;
  serverMax: string;
}

export interface SecondFactor {
  id: string;
  kind: "otp" | "security-key" | "recovery-codes";
  label?: string;
  createdAt?: string;
  /** Recovery codes left, for recovery codes only. */
  remaining?: number;
}

export interface TwoFactorStatus {
  policy: "none" | "admins" | "all";
  /** The policy covers this user. */
  required: boolean;
  /** This session signed in with a second factor. */
  thisSession: boolean;
  /** SFTP accepts a password plus a one-time code. */
  sftpCodes: boolean;
  /** Null when Keycloak could not be asked. */
  factors: SecondFactor[] | null;
}

export type EntryType = "AREA" | "DIR" | "FILE" | "SHARED_FOLDER" | "FORWARD_FOLDER";

export interface Entry {
  name: string;
  path: string;
  type: EntryType;
  size: number;
  modified?: string;
  writable: boolean;
  folderId?: string;
  /** When the file is deleted automatically, if it has an expiry. */
  expiresAt?: string;
  /** The expiry it was given ("6 days"); absent for a date. */
  expiresIn?: string;
  expirySource?: "upload" | "folderDefault" | "homeDefault" | "changed" | "forwardKeep";
}

/** What an upload into a folder gets. */
export interface FolderExpiry {
  enabled: boolean;
  /** False in forward folders, which keep files by their "After sending" setting. */
  allowed: boolean;
  default?: string;
  max?: string;
  serverMax: string;
}

export interface Listing {
  path: string;
  self: Entry;
  entries: Entry[];
  folderExpiry?: FolderExpiry;
}

export interface UploadSession {
  id: string;
  path: string;
  size: number;
  chunkSize: number;
  chunkCount: number;
  state: "OPEN" | "ASSEMBLING" | "COMPLETED" | "ABORTED" | "EXPIRED" | "FAILED";
  expiresAt: string;
  /** The expiry the file will get when the upload completes. */
  fileExpiresIn?: string;
  error?: string;
  received?: [number, number][];
  bytesReceived?: number;
  file?: Entry;
}

export interface MemberView {
  username: string;
  permission: "READ" | "WRITE";
  /** USER for people, APPLICATION for application accounts. */
  kind?: "USER" | "APPLICATION";
}

/** A user search result for the member picker. */
export interface UserMatch {
  username: string;
  kind: UserKind;
}

export interface FolderView {
  id: string;
  type: "SHARED" | "FORWARD";
  name: string;
  path: string;
  owner: string;
  role: "OWNER" | "READ" | "WRITE";
  destinationId?: string;
  destinationName?: string;
  keepDays?: number;
  paused: boolean;
  members: MemberView[];
  createdAt: number;
  defaultExpiry?: string;
  maxExpiry?: string;
}

export type JobState = "QUEUED" | "SENDING" | "RETRY_WAIT" | "SENT" | "FAILED" | "PAUSED";

export interface ForwardJob {
  id: string;
  fileName: string;
  size: number;
  state: JobState;
  attempts: number;
  lastError?: string;
  createdAt: string;
  sentAt?: string;
  nextAttemptAt?: string;
}

export interface DestinationOption {
  id: string;
  name: string;
  description?: string;
}

export interface HistoryRow {
  id: string;
  fileName: string;
  size: number;
  wentTo: "HOME" | "SHARED" | "FORWARD";
  folderName?: string;
  destinationName?: string;
  pathAtUpload: string;
  currentPath?: string;
  status: string;
  via: "HTTP" | "SFTP";
  clientIp?: string;
  startIp?: string;
  completedAt: string;
  /** The expiry recorded for the upload. */
  expiresAt?: string;
  canDownload: boolean;
  canRetry: boolean;
  jobId?: string;
  folderId?: string;
}

export interface HistoryPage {
  totalCount: number;
  totalBytes: number;
  nextCursor?: string;
  rows: HistoryRow[];
}

export interface SshKey {
  id: string;
  name: string;
  type: string;
  fingerprint: string;
  createdAt: string;
  lastUsedAt?: string;
}

export interface AdminUser {
  id: string;
  username: string;
  email?: string;
  firstName?: string;
  lastName?: string;
  enabled: boolean;
  admin: boolean;
  standard: boolean;
  usedBytes?: number;
  quotaBytes?: number;
  lastLoginAt?: number;
  createdAt: number;
  /** Has a second factor; absent when Keycloak could not be asked. */
  twoFactor?: boolean;
}

export interface CreatedUser {
  user: AdminUser;
  temporaryPassword?: string;
  emailSent: boolean;
}

export interface Destination {
  id: string;
  name: string;
  description?: string;
  host: string;
  port: number;
  remoteDir: string;
  username: string;
  serverAuth: "HOST_KEY" | "HOST_CA";
  hostKeySha256?: string;
  hostCaPublicKey?: string;
  overwrite: boolean;
  enabled: boolean;
  createdAt: number;
  updatedAt: number;
}

export interface KeyView {
  id: string;
  publicKey: string;
  fingerprint: string;
  hasCertificate: boolean;
  state: "ACTIVE" | "PENDING" | "RETIRED";
  createdAt: number;
}

export interface DestinationAdminView {
  destination: Destination;
  keys: KeyView[];
  assignedUsers: string[];
  usedByFolders: string[];
}

export interface TestResult {
  connected: boolean;
  authenticated: boolean;
  hostKeyType?: string;
  hostKeyFingerprint?: string;
  hostCertificate: boolean;
  hostCertificateCa?: string;
  error?: string;
}

export type AppMethod = "MTLS" | "PASSWORD" | "PUBLICKEY";

export interface AppCertificate {
  id: string;
  match: "PINNED" | "SUBJECT";
  sha256?: string;
  subject?: string;
  san?: string;
  notAfter?: number;
  state: "ACTIVE" | "RETIRED";
  createdAt: number;
}

export interface AppSshKey {
  id: string;
  name: string;
  type: string;
  fingerprint: string;
  createdAt: number;
  lastUsedAt?: number;
}

export interface AppAccount {
  id: string;
  username: string;
  description?: string;
  responsible?: string;
  enabled: boolean;
  allowedMethods: AppMethod[];
  allowedSources: string[];
  passwordSetAt?: number;
  passwordExpiresAt?: number;
  certificates: AppCertificate[];
  sshKeys: AppSshKey[];
  /** The soonest password or active certificate expiry. */
  nearestExpiry?: number;
  lastLoginAt?: number;
  createdAt: number;
  usedBytes: number;
  quotaBytes: number;
  homeDefaultExpiry?: string;
}

/** A new account or password; the password is shown once. */
export interface AppCreated {
  account: AppAccount;
  password?: string;
}

/** Encryption at rest, as the admin screen shows it; never any key material. */
export interface EncryptionStatus {
  enabled: boolean;
  provider?: string;
  primaryKeyId?: string;
  allowPlaintextReads: boolean;
  /** Stored objects per KEK id from the last inventory; "plaintext" counts unencrypted ones. */
  inventory: { keyId: string; objects: number; bytes: number; countedAt: string }[];
  job?: {
    id: string;
    scope: "KEY" | "PLAINTEXT";
    fromKeyId?: string;
    state: "SCANNING" | "RUNNING" | "FINISHED" | "CANCELLED" | "FAILED";
    objectsTotal: number;
    objectsDone: number;
    objectsFailed: number;
    bytesDone: number;
    startedAt: string;
    finishedAt?: string;
  };
  recentFailures: { storageKey: string; reason: string; at: string }[];
}

/** A run of text from search results; `match` marks the words that matched. Never HTML. */
export interface Segment {
  text: string;
  match: boolean;
}

export interface SearchHit {
  path: string;
  name: Segment[];
  /** "Home / reports" or "Shared: Invoices / 2026". */
  location: string;
  type: "pdf" | "doc" | "sheet" | "slides" | "text" | "email" | "other" | "folder";
  folder: boolean;
  size: number;
  modified?: string;
  expiresAt?: string;
  snippets: Segment[][];
  /** Whether the contents were searched: ok, or why not. */
  contents: "ok" | "unsupported" | "too_large" | "protected" | "failed" | "pending";
}

export interface SearchResult {
  total: number;
  nextCursor?: string;
  results: SearchHit[];
}

export interface SearchSuggestion {
  path: string;
  name: string;
  location: string;
  folder: boolean;
}

export interface SearchIndexStatus {
  available: boolean;
  documents: number;
  sizeBytes: number;
  appliedSeq: number;
  latestSeq: number;
  lagSeconds: number;
  extract: Record<string, number>;
  rebuilding: boolean;
}
