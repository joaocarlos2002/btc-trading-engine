/** AuthController.Session */
export interface Session {
  username: string;
  /** Without the ROLE_ prefix, e.g. VIEWER, TRADER */
  roles: string[];
}
