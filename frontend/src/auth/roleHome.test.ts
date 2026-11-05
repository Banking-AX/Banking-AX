import { describe, expect, it } from 'vitest'
import { homeFor, isStaffPath, postLoginPath } from './roleHome'

describe('role landing paths', () => {
  it('sends staff to the staff area unless returnTo is already there', () => {
    expect(postLoginPath('STAFF', '/')).toBe('/staff')
    expect(postLoginPath('STAFF', '/tasks/a01/1')).toBe('/staff')
    expect(postLoginPath('STAFF', '/staff/c02/2')).toBe('/staff/c02/2')
  })

  it('keeps customers out of staff paths', () => {
    expect(postLoginPath('CUSTOMER', '/staff')).toBe('/')
    expect(postLoginPath('CUSTOMER', '/staff/c02/2')).toBe('/')
    expect(postLoginPath('CUSTOMER', '/chat/3')).toBe('/chat/3')
  })

  it('does not treat lookalike prefixes as staff paths', () => {
    expect(isStaffPath('/staffing')).toBe(false)
    expect(isStaffPath('/staff?x=1')).toBe(true)
    expect(homeFor('CUSTOMER')).toBe('/')
    expect(postLoginPath('ADMIN', '/staff/c02/2')).toBe('/staff/c02/2')
  })
})
