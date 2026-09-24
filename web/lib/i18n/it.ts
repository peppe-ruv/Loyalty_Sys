// Dizionario unico in italiano (docs/07 §9), pronto a diventare multilingua.

export const it = {
  app: {
    name: "Loyalty Hub",
    pitch: "Piattaforma loyalty open source ed event-driven: azioni → punti → premi, con motivazione registrata.",
    repo: "Repository",
    demoBanner: "Ambiente dimostrativo: dati fittizi, nessuna autenticazione.",
  },
  hub: {
    statusTitle: "Stato dei servizi",
    wake: "Accendi la demo",
    waking: "Avvio in corso…",
    ready: (n: number, tot: number) => `pronti ${n}/${tot}`,
    firstStart:
      "Il primo avvio richiede 1–3 minuti: i servizi gratuiti si addormentano quando nessuno li usa.",
    kafkaDown:
      "Il cluster Kafka gratuito potrebbe essere stato spento per inattività: va riacceso dalla console del fornitore.",
    kafkaDownLink: "Come riaccenderlo (docs/11 §3)",
    elapsed: (t: string) => `tempo trascorso ${t}`,
    readyIn: (t: string) => `pronta in ${t}`,
    statusError: "Impossibile leggere lo stato dei servizi.",
    statusStale: "Stato non aggiornato: ultimo controllo fallito.",
    retry: "Riprova",
    idle: "Aggiornamento in pausa dopo 45 minuti senza interazione: tocca la pagina per riprendere.",
    entrancesTitle: "Da dove entrare",
    backoffice: "Backoffice",
    portal: "Portale",
    entrancesLocked: "Gli ingressi si attivano quando ingestion, member, campaign e wallet sono attivi.",
    choosePersona: "Scegli la persona con cui entrare",
    chooseMember: "Scegli il membro con cui entrare",
    membersEmpty: "Nessun membro selezionabile",
    membersEmptyHint: "I dati demo non risultano caricati: ripristinali dalla Console demo.",
    openConsole: "Apri la Console demo",
    enterError: "Non è stato possibile impostare la persona. Riprova.",
    pathTitle: "Percorso consigliato",
  },
  states: {
    UP: "Attivo",
    WAKING: "In avvio",
    DOWN: "Spento",
    SLEEPING: "Dormiente",
  },
  shell: {
    comingSoon: "In arrivo",
    backofficeEmpty:
      "Il backoffice prende forma con la milestone M1: qui compariranno panoramica, campagne, membri e la console demo.",
    portalEmpty: "Il portale membri arriva con la milestone M1: tessera, saldo e movimenti.",
    backToHub: "← Demo Hub",
  },
} as const;
