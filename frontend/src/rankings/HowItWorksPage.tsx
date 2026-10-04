import { useNavigate } from 'react-router'
import back from '../assets/icons/back.svg'
import extend from '../assets/icons/extend.svg'
import diagram from '../assets/icons/how-diagram.svg'
import merge from '../assets/icons/merge.svg'
import shield from '../assets/icons/shield.svg'
import star from '../assets/icons/star.svg'

const RULES = [
  { icon: star, title: 'Your preference sets the points', text: '#1 adds 3 points to every stretch it covers, #2 adds 2, #3 adds 1.' },
  {
    icon: merge,
    title: 'Stretches are named simply',
    text: 'Neighbouring roads with similar scores show as one entry, named by the towns at each end — the map stays exact.',
  },
  {
    icon: extend,
    title: 'Extensions count only the new part',
    text: 'Extending Route 99 to Horana scores only Makumbura → Horana — but the whole route (21.4 + 15.4 km) must still fit in 40 km.',
  },
  {
    icon: shield,
    title: 'Fair play',
    text: 'One Google account per person. Each slot can change once every 24 h. New accounts count after 24 h.',
  },
]

/** /how-it-works — W12 */
export function HowItWorksPage() {
  const navigate = useNavigate()
  return (
    <main className="page how-it-works">
      <div className="top-bar">
        <button type="button" className="round-button" onClick={() => (history.length > 1 ? navigate(-1) : navigate('/leaderboard'))}
          aria-label="Back">
          <img src={back} alt="" width={20} height={20} />
        </button>
        <h1>How ranking works</h1>
      </div>
      <section className="how-example">
        <h2>Votes add up on each stretch of road</h2>
        <figure className="how-diagram">
          <img src={diagram} alt="" width={338} height={196} />
          <span style={{ left: 26, top: 16 }}>Person 1 · A→D as #1 · +3</span>
          <span style={{ left: 26, top: 48 }}>Person 2 · A→C as #2 · +2</span>
          <span style={{ left: 125, top: 80 }}>Person 3 · B→C as #1 · +3</span>
          <b style={{ left: 16 }}>A</b>
          <b style={{ left: 115 }}>B</b>
          <b style={{ left: 214 }}>C</b>
          <b style={{ left: 314 }}>D</b>
          <em style={{ left: 47 }}>5 pts</em>
          <em className="top" style={{ left: 146 }}>8 pts</em>
          <em style={{ left: 245.5 }}>3 pts</em>
          <figcaption className="visually-hidden">
            Person 1 votes A to D as their #1 (+3), person 2 votes A to C as their #2 (+2), person 3 votes B to C as
            their #1 (+3): A–B gets 5 points, B–C 8 and C–D 3.
          </figcaption>
        </figure>
        <p>B → C is covered by all three routes, so it ranks highest. Rankings update every 30 minutes.</p>
      </section>
      {RULES.map((r) => (
        <section key={r.title} className="how-rule">
          <span className="how-icon">
            <img src={r.icon} alt="" width={20} height={20} />
          </span>
          <div>
            <h2>{r.title}</h2>
            <p>{r.text}</p>
          </div>
        </section>
      ))}
    </main>
  )
}
