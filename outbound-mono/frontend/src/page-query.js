import { onScopeDispose } from 'vue';
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router';

// Async work belongs to the page that started it, including while the next route loads.
export function usePageQuery() {
  const route = useRoute(),
    router = useRouter(),
    path = route.path;
  let disposed = false,
    leavingTo = null;
  onBeforeRouteLeave((to) => {
    leavingTo = to;
  });
  const removeAfterEach = router.afterEach((to, from, failure) => {
    // A cancelled navigation must leave the current page's filters usable.
    // Cancellation of an older query update must not unlock a newer pending leave.
    if (!disposed && router.currentRoute.value.path === path && (!failure || to === leavingTo))
      leavingTo = null;
  });
  onScopeDispose(() => {
    disposed = true;
    removeAfterEach();
  });
  const isCurrent = () => !disposed && !leavingTo && router.currentRoute.value.path === path;
  const replaceQuery = (query) =>
    isCurrent() ? router.replace({ path, query }) : Promise.resolve();
  return { isCurrent, replaceQuery };
}
