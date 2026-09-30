import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { it as t } from "@/lib/i18n/it";
import { EnterpriseShowcaseLink } from "./EnterpriseShowcaseLink";

// HUB-01, F2-DIST-09, ADR-049: il pulsante verso la vetrina Enterprise.

const URL_OK = "https://showcase.example.org";

describe("EnterpriseShowcaseLink", () => {
  it("senza URL il pulsante non c'è", () => {
    const { container } = render(<EnterpriseShowcaseLink env={{}} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("con un URL non valido il pulsante non c'è", () => {
    const { container } = render(<EnterpriseShowcaseLink env={{ LH_HUB_ENTERPRISE_URL: "http://showcase.example.org" }} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("con un'origine https valida è un link nella stessa scheda, con rel noopener e sottotitolo", () => {
    render(<EnterpriseShowcaseLink env={{ LH_HUB_ENTERPRISE_URL: `${URL_OK}/` }} />);
    const link = screen.getByRole("link", { name: new RegExp(t.hub.enterpriseCta) });
    expect(link).toHaveAttribute("href", URL_OK);
    expect(link).toHaveAttribute("rel", "noopener");
    expect(link).not.toHaveAttribute("target");
    expect(link).toHaveAccessibleName(`${t.hub.enterpriseCta} ${t.hub.enterpriseExternal}`);
    expect(link).toHaveAccessibleDescription(t.hub.enterpriseNote);
    expect(screen.getByText("istanza separata · login reale · dati fittizi · non HA")).toBeInTheDocument();
  });

  it("nel profilo enterprise è nascosto anche con un URL valido", () => {
    const { container } = render(
      <EnterpriseShowcaseLink env={{ LH_PROFILE: "enterprise", LH_HUB_ENTERPRISE_URL: URL_OK }} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("un profilo sconosciuto è trattato come enterprise: nascosto", () => {
    const { container } = render(<EnterpriseShowcaseLink env={{ LH_PROFILE: "boh", LH_HUB_ENTERPRISE_URL: URL_OK }} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("nel profilo demo esplicito è visibile", () => {
    render(<EnterpriseShowcaseLink env={{ LH_PROFILE: "demo", LH_HUB_ENTERPRISE_URL: URL_OK }} />);
    expect(screen.getByRole("link")).toHaveAttribute("href", URL_OK);
  });
});
