export interface UserRole {
  name: string;
  color: string;
}

export interface ScoreData {
  username: string;
  handle: string;
  userId: string;
  avatarUrl: string;
  bannerUrl: string | null;
  accentColor: string;
  score: number;
  accountCreatedAt: string;
  joinedServerAt: string | null;
  roles: UserRole[];
}
