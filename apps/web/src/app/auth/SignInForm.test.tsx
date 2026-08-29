import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { SignInForm } from "./SignInForm";

describe("SignInForm", () => {
  it("disables submit while the token field is empty", () => {
    render(<SignInForm onSignIn={vi.fn()} />);
    expect(screen.getByRole("button", { name: "Sign in" })).toBeDisabled();
  });

  it("keeps the token masked and autocomplete off", () => {
    render(<SignInForm onSignIn={vi.fn()} />);
    const input = screen.getByLabelText("Development access token");
    expect(input).toHaveAttribute("type", "password");
    expect(input).toHaveAttribute("autoComplete", "off");
  });

  it("submits the trimmed token and clears the field", () => {
    const onSignIn = vi.fn();
    render(<SignInForm onSignIn={onSignIn} />);

    const input = screen.getByLabelText("Development access token");
    fireEvent.change(input, { target: { value: "  tkn-123  " } });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(onSignIn).toHaveBeenCalledWith("tkn-123");
    expect(screen.getByLabelText("Development access token")).toHaveValue("");
    expect(screen.getByRole("button", { name: "Sign in" })).toBeDisabled();
  });

  it("does not submit when the field contains only whitespace", () => {
    const onSignIn = vi.fn();
    const { container } = render(<SignInForm onSignIn={onSignIn} />);

    fireEvent.change(screen.getByLabelText("Development access token"), {
      target: { value: "   " },
    });
    const form = container.querySelector("form");
    if (form === null) {
      throw new Error("Expected the sign-in form to be present.");
    }
    fireEvent.submit(form);

    expect(onSignIn).not.toHaveBeenCalled();
  });
});