import { useQuery } from "@tanstack/react-query";
import { get, setCsrfToken } from "../api/client";
import type { Me } from "../api/types";

/** The signed-in user. A 401 sends the browser to sign in (see api/client). */
export function useMe() {
  return useQuery({
    queryKey: ["me"],
    queryFn: async () => {
      const me = await get<Me>("/api/v1/me");
      setCsrfToken(me.csrfToken);
      return me;
    },
    staleTime: 30_000,
  });
}
