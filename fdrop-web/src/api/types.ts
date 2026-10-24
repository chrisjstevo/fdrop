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
}

export interface Listing {
  path: string;
  self: Entry;
  entries: Entry[];
}

export interface UploadSession {
  id: string;
  path: string;
  size: number;
  chunkSize: number;
  chunkCount: number;
  state: "OPEN" | "ASSEMBLING" | "COMPLETED" | "ABORTED" | "EXPIRED" | "FAILED";
  expiresAt: string;
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
}

/** A new account or password; the password is shown once. */
export interface AppCreated {
  account: AppAccount;
  password?: string;
}
