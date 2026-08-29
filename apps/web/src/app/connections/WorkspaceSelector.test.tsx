import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { workspaceOne, workspaceTwo } from "../../test/fixtures";
import { WorkspaceSelector } from "./WorkspaceSelector";

describe("WorkspaceSelector", () => {
  it("lists workspaces with their role badges and selects on click", () => {
    const onSelect = vi.fn();
    render(
      <WorkspaceSelector
        workspaces={[workspaceOne, workspaceTwo]}
        nextCursor={null}
        onSelect={onSelect}
      />,
    );

    expect(screen.getByRole("list", { name: "Available workspaces" })).toBeVisible();
    expect(screen.getByRole("button", { name: /Acme Analytics/ })).toBeVisible();
    expect(screen.getByText("ADMINISTRATOR")).toBeVisible();

    fireEvent.click(screen.getByRole("button", { name: /Acme Analytics/ }));
    expect(onSelect).toHaveBeenCalledWith("wsp_one");
  });

  it("shows an empty state when there are no workspaces", () => {
    render(<WorkspaceSelector workspaces={[]} nextCursor={null} onSelect={vi.fn()} />);
    expect(screen.getByRole("heading", { name: "No workspaces" })).toBeVisible();
  });

  it("announces pagination when a next cursor is present", () => {
    render(
      <WorkspaceSelector
        workspaces={[workspaceOne]}
        nextCursor="cursor-01"
        onSelect={vi.fn()}
      />,
    );
    expect(screen.getByText("Only the first page of workspaces is shown.")).toBeVisible();
  });
});