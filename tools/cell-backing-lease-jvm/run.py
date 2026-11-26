"""Source invariant: a cell handed to a live task must never re-show its page-colour placeholder.

The Host paints a near-white `HyperOsStyle.page` placeholder behind every side cell so a
translucent app cannot reveal the wallpaper. While the workspace is zooming or a cell is
being handed to a real task, that View is leased to alpha 0 by `LiveSwapVisualLease`.
Two things used to break that lease and flash the placeholder for a frame while the
incoming task still had no buffer:

  * `updateWorkspaceBacking` wrote `view.setAlpha(...)` straight onto the View, bypassing
    the lease bookkeeping, so any later workspace alpha pass resurrected the placeholder;
  * the live collect/swap finish transaction revealed `slot.cellBackground` (the per-cell
    page-colour SurfaceControl) in the very same transaction that reparented the task;
  * live collect leased only the backing, not the empty-cell hint. The destination cell is
    still `occupied == false` until the settle completes, so a late `updateHint()` (every
    `TaskView#onInitialized` posts one) re-showed the near-white `HyperOsStyle.emptyCell`
    gradient on top of the incoming task for a frame.

All are structural, so this suite asserts the shape of the source rather than a runtime.
"""
from pathlib import Path
import sys as _sys, pathlib as _pathlib; _sys.path.insert(0, str(_pathlib.Path(__file__).resolve().parents[1])); from source_paths import package_dir

root = Path(__file__).resolve().parents[2]
text = (package_dir(root)/'WorkspaceHost.java').read_text(encoding='utf-8-sig')
checks = 0


def check(value, reason):
    global checks
    checks += 1
    if not value:
        raise AssertionError(reason)


def member(signature):
    start = text.index(signature)
    brace = text.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        if text[end] == '{':
            depth += 1
        elif text[end] == '}':
            depth -= 1
        end += 1
    return text[start:end]


# The workspace backing is shared chrome: it must obey the lease, never write the View.
backing = member('    private void updateWorkspaceBacking(View view')
check('LiveSwapVisualLease.writeAlpha(view,' in backing,
      'updateWorkspaceBacking must fade through LiveSwapVisualLease.writeAlpha')
check('view.setAlpha(' not in backing,
      'updateWorkspaceBacking must not write the leased View alpha directly: a leased cell placeholder would flash its page colour')

# The lease must still be the only place that can make a held View visible.
lease = (package_dir(root)/'LiveSwapVisualLease.java').read_text(encoding='utf-8-sig')
check('view.setAlpha(0f)' in lease, 'lease still hides on acquire')
check('hold.alpha = alpha' in lease, 'writeAlpha still defers while held')
check('view.setAlpha(hold.alpha)' in lease, 'release still restores the last requested alpha')

# Live collect: the empty destination cell must not reveal a stale backing in the settle transaction.
collect = member('    private void startLiveCollect(')
check('hostSurface, null,' in collect,
      'live collect must not hand slot.cellBackground to the settle transaction')
check('hostSurface, slot.cellBackground,' not in collect,
      'live collect still reveals the destination placeholder inside the settle transaction')
check('new LiveSwapVisualLease(sideBackingViews[slot.index], slot.hint)' in collect,
      'live collect must lease the empty-cell hint too: a late updateHint() would re-show its near-white gradient over the incoming task')
check('new LiveSwapVisualLease(sideBackingViews[slot.index], null)' not in collect,
      'live collect leases only the backing, so the empty-cell hint can flash during the handoff')

# Live flip: only the moving cell is withheld; peers keep their opaque backing.
swap = member('    private Object prepareLiveFlip(')
check('cellBackgrounds[i - 1] = peer == ticket.slot ? null : peer.cellBackground;' in swap,
      'live flip must withhold only the moving cell backing and keep peer backings')
check('cellBackgrounds[i - 1] = peer.cellBackground;' not in swap,
      'live flip still reveals the moving cell placeholder inside the settle transaction')

# Live Home promotion: the promoted cell is the moving one.
home = member('    private void startLiveHome(')
check('new Object[]{null}, ticket.liveBackdrop);' in home,
      'live Home must not reveal the promoted cell placeholder inside the settle transaction')
check('new Object[]{ticket.slot.cellBackground}, ticket.liveBackdrop);' not in home,
      'live Home still reveals the promoted cell placeholder inside the settle transaction')

# The placeholder itself is intentionally still allocated under a live task.
ensure = member('    private void ensureCellBackground(')
check('HyperOsStyle.page(ctx)' in ensure,
      'occupied cells still own a page-colour backing so translucent apps cannot reveal wallpaper')

print('actual cell backing lease/handoff source checks=%d passed' % checks)
